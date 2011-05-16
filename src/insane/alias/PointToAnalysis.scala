package insane
package alias

import utils._
import analysis._
import CFG.ControlFlowGraph

trait PointToAnalysis extends PointToGraphsDefs {
  self: AnalysisComponent =>

  import global._
  import PointToGraphs._

  var predefinedPTClasses = Map[Symbol, PTEnv]()
  var predefinedPTMethods = Map[Symbol, PTEnv]()

  def getPTEnv(sym: Symbol): Option[PTEnv] = {
    pointToEnvs.get(sym) orElse predefinedPTMethods.get(sym) orElse predefinedPTClasses.get(sym.owner)
  }

  case class PTEnv(ptGraph: PointToGraph,
                 locState: Map[CFG.Ref, Set[Node]],
                 iEdges: Set[IEdge],
                 oEdges: Set[OEdge],
                 eNodes: Set[Node],
                 rNodes: Set[Node],
                 isBottom: Boolean) extends DataFlowEnvAbs[PTEnv, CFG.Statement] {

    def this(isBottom: Boolean = false) = this(new PointToGraph(), Map().withDefaultValue(Set()), Set(), Set(), Set(), Set(), isBottom)

    def union(that: PTEnv) = {
      copy(ptGraph union that.ptGraph,
        (locState.keySet ++ that.locState.keySet).map(k => k -> (locState(k)++that.locState(k))).toMap.withDefaultValue(Set()),
        iEdges union that.iEdges,
        oEdges union that.oEdges,
        eNodes union that.eNodes,
        rNodes union that.rNodes,
        false)
    }

    def reachableNodes(from: Set[Node], via: Set[Edge]): Set[Node] = {
      var res = from
      var queue = from

      while(!queue.isEmpty) {
        val n = queue.head
        queue = queue.tail

        val toAdd = via collect { case Edge(v1, _, v2) if (v1 == n) && !(res contains v2) => v2 }

        queue = queue ++ toAdd
        res = res ++ toAdd
      }

      res
    }

    def escapingNodes: Set[Node] = {
      val from = (ptGraph.vertices.collect { case n: LNode => n; case n: PNode => n }) ++ eNodes + GBNode

      reachableNodes(from, Set() ++ iEdges)
    }

    def processLoad(vRef: CFG.Ref, vFrom: CFG.Ref, field: Field, pPoint: Int) = {
      val nodesFromVFrom = iEdges.collect { case Edge(v1,l,v2) if (l == field) && (getL(vFrom) contains v1) => v2 }
      val escapings      = escapingNodes intersect getL(vFrom)

      if (escapings.isEmpty) {
        // If nodes of vFrom are not escaping the current scope:
        copy(locState = locState + (vRef -> nodesFromVFrom))
      } else {
        val lNode = LNode(pPoint)
        addNode(lNode).setL(vRef, (nodesFromVFrom + lNode)).addOEdges(escapings, field, Set(lNode))
      }
    }

    def setL(ref: CFG.Ref, nodes: Set[Node]) = {
      copy(locState = locState + (ref -> nodes), isBottom = false)
    }

    def getL(ref: CFG.Ref): Set[Node] = locState(ref)

    def addNode(node: Node) =
      copy(ptGraph = ptGraph + node, isBottom = false)

    def addOEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      var newGraph = ptGraph
      var oEdgesNew = oEdges
      for (v1 <- lv1; v2 <- lv2) {
        val e = OEdge(v1, field, v2)
        newGraph += e
        oEdgesNew += e
      }
      copy(ptGraph = newGraph, oEdges = oEdgesNew, isBottom = false)
    }

    def addIEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      var newGraph = ptGraph
      var iEdgesNew = iEdges
      for (v1 <- lv1; v2 <- lv2) {
        val e = IEdge(v1, field, v2)
        newGraph += e
        iEdgesNew += e
      }
      copy(ptGraph = newGraph, iEdges = iEdgesNew, isBottom = false)
    }

    def addENodes(nodes: Set[Node]) = {
      copy(ptGraph = ptGraph ++ nodes, eNodes = eNodes ++ nodes, isBottom = false)
    }

    def setReturnNodes(ref: CFG.Ref) = {
      val nodes = getL(ref)
      copy(ptGraph = ptGraph ++ nodes, rNodes = nodes, isBottom = false)
    }

    def addGlobalNode() = {
      copy(ptGraph = ptGraph + GBNode, isBottom = false)
    }

    def duplicate = this
  }

  object BottomPTEnv extends PTEnv(true)

  class PointToAnalysisPhase extends SubPhase {
    val name = "Point-to Analysis"

    class PointToTF(cfg: FunctionCFG) extends TransferFunctionAbs[PTEnv, CFG.Statement] {

      def apply(st: CFG.Statement, oldEnv: PTEnv): PTEnv = {
        var env = oldEnv

        def getNodesFromEnv(e: PTEnv)(sv: CFG.SimpleValue): Set[Node] = sv match {
          case r2: CFG.Ref => e.getL(r2)
          case n : CFG.Null => Set()
          case _ => Set()
        }

        case class NodeMap(map: Map[Node, Set[Node]] = Map().withDefaultValue(Set())) extends Function1[Node, Set[Node]] {

          override def toString() = map.toString()

          def apply(n: Node): Set[Node] = map(n)

          def +(ns: (Node, Node)) = {
            copy(map = map + (ns._1 -> (map(ns._1)++Set(ns._2))))
          }

          def ++(ns: (Node, Set[Node])) = {
            copy(map = map + (ns._1 -> (map(ns._1) ++ ns._2)))
          }

          def +++(ns: Seq[(Node, Set[Node])]) = {
            copy(map = map ++ (ns.map(nn => (nn._1 -> (map(nn._1) ++ nn._2)))))
          }
        }

        type Transformer = (PTEnv, NodeMap) => (PTEnv, NodeMap)

        def getNodes(sv: CFG.SimpleValue) = getNodesFromEnv(env)(sv)

        // Merging graphs  of callees into the caller
        def interProc(eCaller: PTEnv, target: Symbol, call: CFG.AssignApplyMeth): PTEnv = {

          println("@@@ Analyzing call "+call+" to method "+target.fullName)

          def p(env: PTEnv) = env.copy(locState = Map().withDefaultValue(Set()))
          def gc(env: PTEnv) = env // TODO
          def pi(env: PTEnv) = env // TODO

          def transFixPoint(envCallee: PTEnv, envInit: PTEnv, mapInit: NodeMap): (PTEnv, NodeMap) = {
            var env  = envInit
            var nmap = mapInit

            // Atomic transformers
            def gesc(n: Node) {
              env = env.copy(eNodes = env.eNodes ++ nmap(n))
            }

            def store(e: Edge) {
              println("# Processing "+e)
              env = env.addIEdges(nmap(e.v1), e.label, nmap(e.v2))
            }

            def load(e: Edge) {
              println("# Processing "+e)
              val existingViaIEdge = nmap(e.v1) flatMap (n1 => (env.ptGraph.E collect { case oe if oe.v1 == n1 &&  oe.label == e.label => oe.v2})) 
              println("# Exist via iE "+existingViaIEdge)
              val newMap = nmap ++ (e.v2 -> existingViaIEdge)
              println("# NewMap "+newMap)

              val a = nmap(e.v1) intersect env.escapingNodes
              println("# a "+ a)

              if (a.isEmpty) {
                nmap = newMap
                if (existingViaIEdge.isEmpty) {
                  // Add oEdge
                  println("# Adding oEdge")
                  env = env.addOEdges(nmap(e.v1), e.label, Set(e.v2))
                  nmap = nmap + (e.v2 -> e.v2)
                }
              } else {
                env = env.addOEdges(a, e.label, Set(e.v2))
                nmap = newMap + (e.v2 -> e.v2)
              }
            }

            // Apply all transformers
            var lastEnv  = env
            var lastnmap = nmap

            var i = 0

            do {
              lastEnv  = env
              lastnmap = nmap

              i += 1

              println("Map At("+i+"): "+nmap)

              for (eN <- envCallee.eNodes) {
                gesc(eN)
              }

              for (iE <- envCallee.iEdges) {
                store(iE)
              }

              for (oE <- envCallee.oEdges) {
                load(oE)
              }

            } while (lastEnv != env || lastnmap != nmap)

            println("Map After: "+nmap)

            (env, nmap)
          }

          def callerNodes(sv: CFG.SimpleValue) = getNodesFromEnv(eCaller)(sv)

          val oeCallee = getPTEnv(target)
          if (!oeCallee.isEmpty) {
            val eCallee = oeCallee.get

            val gcCallee = pi(gc(p(eCallee)))

            val map: NodeMap = NodeMap() +++ (call.obj +: call.args).zipWithIndex.flatMap{ case (sv, i) => callerNodes(sv) map (n => (PNode(i), Set(n))) } +++ (eCallee.ptGraph.vertices.toSeq.collect{ case n: INode => (n: Node,Set[Node](n)) }) + (GBNode -> GBNode)

            println("Node map is : "+map)

            val (newEnvTmp, newMap) = transFixPoint(gcCallee, eCaller, map)

            val newEnv = newEnvTmp.setL(call.r, gcCallee.rNodes flatMap newMap)

            newEnv
          } else {
            reporter.error("Unknown env for target "+target+" for call: "+call)
            eCaller
          }
        }

        st match {
          case av: CFG.AssignVal =>
            env = env.setL(av.r, getNodes(av.v))

          case afr: CFG.AssignFieldRead =>
            afr.obj match {
              case sr: CFG.SymRef if sr.symbol.isModule =>
                // If we have r = obj.field where obj is a global object, we have that r is pointing to GLB
                env = env.addGlobalNode().setL(afr.r, Set(GBNode))
              case _ =>
                env = env.processLoad(afr.r, afr.obj, SymField(afr.field), afr.uniqueID)
            }
          case afw: CFG.AssignFieldWrite =>
            afw.obj match {
              case sr: CFG.SymRef if sr.symbol.isModule =>
                // If we do Obj.field = rhs, where Obj is a global object, rhs is escaping from the scope
                env = env.addENodes(getNodes(afw.rhs))
              case _ =>
                // Otherwise, we have obj.field = rhs
                env = env.addIEdges(getNodes(afw.obj), SymField(afw.field), getNodes(afw.rhs))
            }

          case aam: CFG.AssignApplyMeth => // r = o.v(..args..)

            val (targets, optError) = callTargets.get(aam) match {
              case Some((targets, exhaust)) if !exhaust && !settings.wholeCodeAnalysis =>
                (Set(), Some("targets are not exhaustive"))

              case Some((targets, exhaust)) =>
                val unanalyzable = targets.filter(t => getPTEnv(t).isEmpty)
                if (!unanalyzable.isEmpty) {
                  (Set(), Some("targets "+unanalyzable.mkString(", ")+" have no corresponding PT env"))
                } else {
                  (targets, None)
                }

              case _ =>
                (Set(), Some("no target symbol could be found"))
            }



            if (optError.isEmpty) {
              env = ((BottomPTEnv:PTEnv) /: targets) ((e, sym) => e union interProc(env, sym, aam))
            } else {
              settings.ifVerbose {
                reporter.warn("Aborted call analysis of "+aam+" because "+optError.get)
              }

              env = env.addGlobalNode().setL(aam.r, Set(GBNode))

              env = (env /: (aam.obj +: aam.args)) { (e, a) => e.addENodes(getNodes(a)) }
            }

          case an: CFG.AssignNew => // r = new A
            val iNode = INode(an.uniqueID)
            env = env.addNode(iNode).setL(an.r, Set(iNode))

          case aa: CFG.AssignArray =>
            // TODO: Implement rare use-cases
            reporter.error("Ignored AssignArray:" + aa + " at "+aa.pos)

          case ac: CFG.AssignCast =>
            env = env.setL(ac.r, env.getL(ac.rhs))

          case _ =>
        }
        env
      }

    }

    def analyze(fun: AbsFunction) = {
      val cfg       = fun.cfg.get
      val bottomEnv = BottomPTEnv
      var baseEnv   = new PTEnv()

      reporter.info("Analyzing "+fun.symbol.fullName+"...")

      // 1) We add 'this' and argument nodes
      for ((a, i) <- (cfg.thisRef +: fun.CFGArgs).zipWithIndex) {
        val pNode = PNode(i)
        baseEnv = baseEnv.addNode(pNode).setL(a, Set(pNode))
      }


      // 2) We run a fix-point on the CFG
      val ttf = new PointToTF(cfg)
      val aa = new DataFlowAnalysis[PTEnv, CFG.Statement](bottomEnv, baseEnv, settings)

      aa.computeFixpoint(cfg, ttf)

      // 3) We retrieve the exit CFG
      val e = aa.getResult(cfg.exit).setReturnNodes(cfg.retval)

      pointToEnvs += fun.symbol -> e
    }

    def analyzeSCC(scc: Set[Symbol]) {
      // The analysis is only run on symbols that are actually AbsFunctions, not all method symbols
      println("Analyzing a group of methods: "+scc)

      var workList = scc

      // 1) First, we add BottomPTEnv to every functions that we will analyze
      for (sym <- scc) {
        if (funDecls contains sym) {
          if (!(pointToEnvs contains sym)) {
            pointToEnvs += sym -> BottomPTEnv
          }
        } else {
          if (getPTEnv(sym).isEmpty) {
            reporter.warn("Ignoring the analysis of unknown methods: "+sym.fullName)
          }
          workList -= sym
        }
      }

      // 2) Then, we analyze every methods until we reach a fixpoint
      while(!workList.isEmpty) {
        val sym = workList.head
        workList = workList.tail

        if (funDecls contains sym) {
          val fun = funDecls(sym)

          val eBefore = pointToEnvs.get(sym)
          analyze(fun)
          val eAfter = pointToEnvs.get(sym)

          if (eBefore != eAfter) {
            workList ++= (simpleReverseCallGraph(sym) & scc)
          }
        }
      }
    }

    def run() {
      // 1) Fill ignore lists for pure but not analyzable classes/methods
      predefinedPTClasses += definitions.ObjectClass -> BottomPTEnv

      // 2) Analyze each SCC in sequence, in the reverse order of their topological order
      //    We first analyze {M,..}, and then methods that calls {M,...}
      val workList = callGraphSCCs.reverse.map(scc => scc.vertices.map(v => v.symbol))
      for (scc <- workList) {
        analyzeSCC(scc)
      }

      // 3) Display/dump results, if asked to
      if (!settings.dumpptgraphs.isEmpty) {
        for ((s, fun) <- funDecls if settings.dumpPTGraph(s.fullName)) {

          val name = fun.symbol.fullName
          val cfg = fun.cfg.get

          pointToEnvs.get(fun.symbol) match {
            case Some(e) =>
              var newGraph = e.ptGraph

              // We complete the graph with local vars -> nodes association, for clarity
              for ((ref, nodes) <- e.locState if ref != cfg.retval; n <- nodes) {
                newGraph += VEdge(VNode(ref), n)
              }

              val dest = name+"-pt.dot"

              reporter.info("Dumping Point-To Graph to "+dest+"...")
              new PTDotConverter(newGraph, "Point-to: "+name, e.rNodes, e.eNodes).writeFile(dest)
            case None =>
              reporter.warn("Could not find a point-to graph for "+name)
          }

        }
      }
    }
  }
}

