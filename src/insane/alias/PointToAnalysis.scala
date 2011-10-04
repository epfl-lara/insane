package insane
package alias

import storage.Database

import utils._
import utils.Reporters._
import CFG.ControlFlowGraph

import scala.reflect.generic.Flags

trait PointToAnalysis extends PointToGraphsDefs {
  self: AnalysisComponent =>

  import global._
  import PointToGraphs._

  def getPTEnvFromFunSym(sym: Symbol): Option[PTEnv] = funDecls.get(sym).map(_.pointToResult)

  var predefinedPriorityEnvs = Map[Symbol, Option[PTEnv]]()

  def getPredefPriorityEnv(sym: Symbol): Option[PTEnv] = predefinedPriorityEnvs.get(sym) match {
    case Some(optPTEnv) => optPTEnv
    case None =>
      if (Database.active) {
        val optEnv = Database.Env.lookupPriorityEnv(uniqueFunctionName(sym)).map(s => EnvUnSerializer(s).unserialize)
        predefinedPriorityEnvs += sym -> optEnv

        optEnv
      } else {
        None
      }
  }

  var predefinedEnvs = Map[Symbol, Option[PTEnv]]()

  def getPredefEnv(sym: Symbol): Option[PTEnv] = predefinedEnvs.get(sym) match {
    case Some(optPTEnv) => optPTEnv
    case None =>
      if (Database.active) {
        val optEnv = Database.Env.lookupEnv(uniqueFunctionName(sym)).map(s => EnvUnSerializer(s).unserialize)
        predefinedEnvs += sym -> optEnv
        optEnv
      } else {
        None
      }
  }

  def getPTEnv(sym: Symbol): Option[PTEnv] = {
    getPredefPriorityEnv(sym) orElse getPTEnvFromFunSym(sym) orElse getPredefEnv(sym)
  }

  def getAllTargetsUsing(edges: Traversable[Edge])(from: Set[Node], via: Field): Set[Node] = {
    edges.collect{ case Edge(v1, f, v2) if (from contains v1) && (f == via) => v2 }.toSet
  }

  class PTEnvCopier() {
    val graphCopier: GraphCopier = new GraphCopier

    def copy(env: PTEnv): PTEnv = {
      PTEnv(
        graphCopier.copy(env.ptGraph),
        env.locState.map{ case (r, nodes) => r -> nodes.map(graphCopier.copyNode _)},
        // env.typeInfo.map{ case (r, otype) => r -> otype },
        env.iEdges.map(graphCopier.copyIEdge _),
        env.oEdges.map(graphCopier.copyOEdge _),
        env.rNodes.map(graphCopier.copyNode _),
        // env.danglingCalls,
        env.isBottom
      )
    }
  }

  class PTEnvReplacer(typeMap: Map[Type, Type], symbolMap: Map[Symbol, Symbol]) extends PTEnvCopier {
    def newSymbol(s: Symbol) = symbolMap.getOrElse(s, s)
    def newType(t: Type)     = typeMap.getOrElse(t, t)

    override val graphCopier = new GraphCopier {
      override def copyNode(n: Node) = n match {
        case OBNode(s) =>
          OBNode(newSymbol(s))
        case _ =>
          super.copyNode(n)
      }

      override def copyTypes(oset: ObjectSet): ObjectSet = {
        ObjectSet(oset.subtypesOf.map(newType _), oset.exactTypes.map(newType _))
      }
    }

  }

  case class PTEnv(ptGraph: PointToGraph,
                 locState: Map[CFG.Ref, Set[Node]],
                 // typeInfo: Map[CFG.Ref, Option[ObjectSet]],
                 iEdges: Set[IEdge],
                 oEdges: Set[OEdge],
                 rNodes: Set[Node],
                 // danglingCalls: Set[DCallNode],
                 isBottom: Boolean) extends dataflow.EnvAbs[PTEnv, CFG.Statement] {

    def this(isBottom: Boolean = false) =
      this(new PointToGraph(),
           Map().withDefaultValue(Set()),
           // Map().withDefaultValue(None),
           Set(),
           Set(),
           Set(),
           // Set(),
           isBottom)

    def clean() = copy(locState = Map().withDefaultValue(Set()))

    val getAllTargets   = getAllTargetsUsing(ptGraph.E)_
    val getWriteTargets = getAllTargetsUsing(iEdges)_
    val getReadTargets  = getAllTargetsUsing(oEdges)_

    def setL(ref: CFG.Ref, nodes: Set[Node]) = {
      copy(locState = locState + (ref -> nodes), isBottom = false)
    }

    def getL(ref: CFG.Ref, readOnly: Boolean): (PTEnv, Set[Node]) = {
      if (locState contains ref) {
        (this, locState(ref))
      } else {
        if (readOnly) {
          reporter.error("Consistency problem: local field accessed without associated nodes in a comp-sub-graph while in read-only context");
          (this, locState(ref))
        } else {
          val n = LVNode(ref, ObjectSet.subtypesOf(ref.tpe))
          (addNode(n).setL(ref, Set(n)), Set(n))
        }
      }
    }

    def replaceNode(from: Node, toNodes: Set[Node]) = {
      assert(!(toNodes contains from), "Recursively replacing "+from+" with "+toNodes.mkString("{", ", ", "}")+"!")

      var newEnv = copy(ptGraph = ptGraph - from ++ toNodes, isBottom = false)

      // Update return nodes
      if (rNodes contains from) {
        newEnv = newEnv.copy(rNodes = rNodes - from ++ toNodes)
      }

      // Update iEdges
      for (iEdge @ IEdge(v1, lab, v2) <- iEdges if v1 == from || v2 == from; to <- toNodes) {
        val newIEdge = IEdge(if (v1 == from) to else v1, lab, if (v2 == from) to else v2)
        
        newEnv = newEnv.copy(ptGraph = ptGraph - iEdge + newIEdge, iEdges = iEdges - iEdge + newIEdge)
      }

      // Update oEdges
      for (oEdge @ OEdge(v1, lab, v2) <- oEdges if v1 == from || v2 == from; to <- toNodes) {
        val newOEdge = OEdge(if (v1 == from) to else v1, lab, if (v2 == from) to else v2)
        
        newEnv = newEnv.copy(ptGraph = ptGraph - oEdge + newOEdge, oEdges = oEdges - oEdge + newOEdge)
      }

      // Update locState
      newEnv = newEnv.copy(locState = locState.map{ case (ref, nodes) => ref -> (if (nodes contains from) nodes - from ++ toNodes else nodes) }.withDefaultValue(Set()))

      /*
      newEnv = newEnv.copy(danglingCalls = newEnv.danglingCalls ++ (toNodes.collect { case dc: DCallNode => dc }))

      from match {
        case dc: DCallNode =>
          newEnv = newEnv.copy(danglingCalls = newEnv.danglingCalls - dc)
        case _ =>
      }
      */

      newEnv
    }

    def addNode(node: Node) =
      copy(ptGraph = ptGraph + node, isBottom = false)

    /*
    def addDanglingCall(dCall: DCallNode) =
      copy(danglingCalls = danglingCalls + dCall, ptGraph = ptGraph + dCall, isBottom = false)
    */

    lazy val loadNodes: Set[LNode] = {
      ptGraph.V.collect { case l: LNode => l }
    }

    /**
     * Corresponds to:
     *   to = {..from..}.field @UniqueID
     */
    def read(from: Set[Node], field: Field, to: CFG.Ref, uniqueID: UniqueID) = {

      var res = this

      var pointResults = Set[Node]()

      for (node <- from) {
        val writeTargets = getWriteTargets(Set(node), field)

        val pointed = if (writeTargets.isEmpty) {
          getReadTargets(Set(node), field)
        } else {
          writeTargets
        }

        if (pointed.isEmpty) {
          safeLNode(node, field, uniqueID) match {
            case Some(lNode) =>
              res = res.addNode(lNode).addOEdge(node, field, lNode)
              pointResults += lNode
            case None =>
              reporter.error("Unable to create LNode from "+node+" via "+field)
          }
        } else {
          pointResults ++= pointed
        }
      }

      res.setL(to, pointResults)
    }

    /**
     * Corresponds to:
     *   {..from..}.field = {..to..} @UniqueID
     */
    def write(from: Set[Node], field: Field, to: Set[Node], allowStrongUpdates: Boolean) = {
      if (from.size == 0) {
        reporter.error("Writing with an empty {..from..} set!")
      }

      if (to.size == 0) {
        reporter.error("Writing with an empty {..to..} set!")
      }

      var newEnv = this

      val isStrong = from.forall(_.isSingleton) && from.size == 1 && allowStrongUpdates

      if (isStrong) {
        // If strong update:

        // 1) We remove all previous write edges
        newEnv = newEnv.removeIEdges(from, field, getWriteTargets(from, field))

        // 2) We add back only the new write edge
        newEnv = newEnv.addIEdges(from, field, to)
      } else {
        // If weak update:

        // For each actual source node:
        for (node <- from) {
          // 1) We check for an old node reachable
          val writeTargets = getWriteTargets(Set(node), field)

          val previouslyPointed = if (writeTargets.isEmpty) {
            getReadTargets(Set(node), field)
          } else {
            writeTargets
          }

          if (previouslyPointed.isEmpty) {
            // We need to add the artificial load node, as it represents the old state
            safeLNode(node, field, new UniqueID(0)) match {
              case Some(lNode) =>
                newEnv = newEnv.addNode(lNode).addOEdge(node, field, lNode).addIEdge(node, field, lNode)
              case None =>
                reporter.error("Unable to create LNode from "+node+" via "+field)
            }
          }

          // 2) We link that to node via a write edge
          newEnv = newEnv.addIEdges(Set(node), field, previouslyPointed ++ to)
        }
      }

      newEnv
    }

    def addOEdge(v1: Node, field: Field, v2: Node) = addOEdges(Set(v1), field, Set(v2))

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

    def addIEdge(v1: Node, field: Field, v2: Node) = addIEdges(Set(v1), field, Set(v2))

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

    def removeIEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      val toRemove = iEdges.filter(e => lv1.contains(e.v1) && lv2.contains(e.v2) && e.label == field)

      copy(ptGraph = (ptGraph /: toRemove) (_ - _), iEdges = iEdges -- toRemove, isBottom = false)
    }

    def removeOEdges(lv1: Set[Node], field: Field, lv2: Set[Node]) = {
      val toRemove = oEdges.filter(e => lv1.contains(e.v1) && lv2.contains(e.v2) && e.label == field)

      copy(ptGraph = (ptGraph /: toRemove) (_ - _), oEdges = oEdges -- toRemove, isBottom = false)
    }

    def setReturnNodes(ref: CFG.Ref) = {
      val (env, nodes) = getNodes(ref)
      env.copy(ptGraph = ptGraph ++ nodes, rNodes = nodes, isBottom = false)
    }

    def addGlobalNode() = {
      copy(ptGraph = ptGraph + GBNode, isBottom = false)
    }

    def stripTypeInconsistencies = {
      // TODO
      this
    }

    def modifiesClause: ModifyClause = {
      import scala.collection.mutable.Stack

      /**
       * Check if there is any reachable IEdge from
       * 1) Params
       * 2) Global Objects
       **/

      var seen    = Set[Node]()
      var effects = Set[ModifyClauseEffect]()

      for (n <- ptGraph.V) n match {
        case _: PNode | _: GloballyReachableNode =>
          visitRoot(n)
        case _ =>
      }

      def visitRoot(n: Node) {
        def visit(n: Node, root: Node, path: List[Field]) {

          seen += n

          for (e @ Edge(v1, via, v2) <- ptGraph.outEdges(n)) {
            val newPath = via :: path

            e match {
              case _: IEdge =>
                effects += ModifyClauseEffect(newPath.reverse, root)
              case _ =>
            }

            if (!seen(v2)) {
              visit(v2, root, newPath)
            }
          }
        }

        visit(n, n, Nil)
      }

      ModifyClause(effects)
    }

    def duplicate = this

    def getNodes(sv: CFG.SimpleValue, readonly: Boolean = false): (PTEnv, Set[Node]) = sv match {
      case r2: CFG.Ref       => getL(r2, readonly)
      case n : CFG.Null      => (this, Set(NNode))
      case u : CFG.Unit      => (this, Set())
      case _: CFG.StringLit  => (this, Set(StringLitNode))
      case _: CFG.BooleanLit => (this, Set(BooleanLitNode))
      case _: CFG.LongLit    => (this, Set(LongLitNode))
      case _: CFG.IntLit     => (this, Set(IntLitNode))
      case _: CFG.CharLit    => (this, Set(CharLitNode))
      case _: CFG.ByteLit    => (this, Set(ByteLitNode))
      case _: CFG.FloatLit   => (this, Set(FloatLitNode))
      case _: CFG.DoubleLit  => (this, Set(DoubleLitNode))
      case _: CFG.ShortLit   => (this, Set(ShortLitNode))
    }

  }

  object BottomPTEnv extends PTEnv(true)

  class PointToAnalysisPhase extends SubPhase {
    val name = "Point-to Analysis"

    object PointToLattice extends dataflow.LatticeAbs[PTEnv, CFG.Statement] {
      val bottom = BottomPTEnv

      def join(envs: PTEnv*): PTEnv = {
        if(envs.size == 1) {
          return envs.head
        }

        /**
         * When merging environment, we need to take special care in case one
         * write edge is not present in the other envs, in that case, it
         * consists of a weak update in the resulting env.
         */

        var newIEdges = envs.flatMap(_.iEdges).toSet
        var newOEdges = envs.flatMap(_.oEdges).toSet
        var newNodes  = envs.flatMap(_.ptGraph.V).toSet

        // 1) We find all nodes that are shared between all envs
        val commonNodes = envs.map(_.ptGraph.V).reduceRight(_ & _)

        // 2) We find all the pair (v1, f) that are not in every env's iEdges
        val allPairs = newIEdges.map(ed => (ed.v1, ed.label)).toSet

        val commonPairs = envs.map(_.iEdges.map(ed => (ed.v1, ed.label)).toSet).reduceRight(_ & _)

        for ((v1, field) <- allPairs -- commonPairs if commonNodes contains v1) {
          // TODO: Is there already a load node for this field?
          safeLNode(v1, field, new UniqueID(0)) match {
            case Some(lNode) =>
              newNodes  += lNode
              newIEdges += IEdge(v1, field, lNode)
              newOEdges += OEdge(v1, field, lNode)
            case None =>
              reporter.error("Unable to create LNode from "+v1+" via "+field)
          }
        }

        val newGraph = new PointToGraph().copy(edges = Set[Edge]() ++ newOEdges ++ newIEdges, vertices = newNodes)

        var typeInfos: Map[CFG.Ref, Map[Node, ObjectSet]] = Map().withDefaultValue(Map())


        val env = new PTEnv(
          newGraph,
          envs.flatMap(_.locState.keySet).toSet.map((k: CFG.Ref) => k -> (envs.map(e => e.locState(k)).reduceRight(_ ++ _))).toMap.withDefaultValue(Set()),
          // envs.flatMap(_.typeInfo.keySet).toSet.map((k: CFG.Ref) => k -> Some(envs.map(e => e.typeInfo(k)).collect{ case Some(s) => s }.reduceRight(_ ++ _))).toMap.withDefaultValue(None),
          newIEdges,
          newOEdges,
          envs.flatMap(_.rNodes).toSet,
          // envs.flatMap(_.danglingCalls).toSet,
          false)

        env
      }

    }

    class PointToTF(fun: AbsFunction) extends dataflow.TransferFunctionAbs[PTEnv, CFG.Statement] {

      def apply(st: CFG.Statement, oldEnv: PTEnv): PTEnv = {
        var env = oldEnv

        case class NodeMap(map: Map[Node, Set[Node]] = Map().withDefaultValue(Set())) extends Function1[Node, Set[Node]] {

          override def toString() = map.toString()

          def apply(n: Node): Set[Node] = map(n)

          def -(node: Node) = {
            copy(map = map - node)
          }

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

        // Merging graphs  of callees into the caller
        def interProcByCall(eCaller: PTEnv, target: Symbol, call: CFG.AssignApplyMeth): PTEnv = {
          def callerNodes(sv: CFG.SimpleValue) = eCaller.getNodes(sv, true)._2 // Readonly getNodes!

          val recNodes  = callerNodes(call.obj)
          val argsNodes = call.args.map(callerNodes(_))

          val (newEnv, retNodes) = interProc(eCaller, target, recNodes, argsNodes, call.uniqueID, true, call.pos)

          newEnv.setL(call.r, retNodes)
        }

        def interProc(eCaller: PTEnv, target: Symbol, recCallerNodes: Set[Node], argsCallerNodes: Seq[Set[Node]], uniqueID: UniqueID, allowStrongUpdates: Boolean, pos: Position): (PTEnv, Set[Node]) = {

          def doFixPoint(envCallee: PTEnv, envInit: PTEnv, nodeMap: NodeMap): PTEnv = {
            var env  = envInit
            var lastEnv  = env
            var i = 0

            do {
              lastEnv  = env
              i += 1

              // We map all edges to their new nodes potentially creating more or less edges
              val mappedEdges = for (IEdge(v1, field, v2) <- envCallee.iEdges; mappedV1 <- nodeMap(v1); mappedV2 <- nodeMap(v2)) yield (IEdge(mappedV1, field, mappedV2), v1)

              for (((newV1, field), edgesOldV1) <- mappedEdges.groupBy { case (edge, oldV1) => (edge.v1, edge.label) }) {
                val (edges, oldV1s) = edgesOldV1.unzip

                // We only allow strong updates if newV1 was the only target of oldV1
                val allowStrong = allowStrongUpdates && oldV1s.forall { nodeMap(_).size == 1 }

                env = env.write(Set(newV1), field, edges.map(_.v2), allowStrong)
              }
            } while (lastEnv != env)

            env
          }


          val oeCallee = getPTEnv(target)
          if (!oeCallee.isEmpty) {
            val eCallee = oeCallee.get

            val gcCallee = eCallee.clean()

            // var newEnv = eCaller.copy(danglingCalls = eCaller.danglingCalls ++ gcCallee.danglingCalls, ptGraph = eCaller.ptGraph ++ gcCallee.danglingCalls)
            var newEnv = eCaller

            // Build map
            var nodeMap: NodeMap = NodeMap()
            for (n <- GBNode :: NNode :: NNode :: BooleanLitNode :: LongLitNode :: DoubleLitNode :: StringLitNode :: IntLitNode :: ByteLitNode :: CharLitNode :: FloatLitNode :: ShortLitNode :: Nil) {
              nodeMap += n -> n
            }

            for (n <- gcCallee.ptGraph.V.filter(_.isInstanceOf[OBNode])) {
              nodeMap += n -> n
            }

            funDecls.get(target) match {
              case Some(fun) =>
                // Found the target function, we assign only object args to corresponding nodes
                nodeMap ++= (fun.pointToArgs(0) -> recCallerNodes)  

                for ((nodes,i) <- argsCallerNodes.zipWithIndex) {
                  fun.pointToArgs(i+1) match  {
                    case pNode: PNode =>
                      nodeMap ++= (pNode -> nodes)
                    case _ =>
                  }
                }

              case None =>
                // Try to use the graph, if any, to map args

                getPTEnv(target) match {
                  case Some(env) =>
                    for (n <- env.ptGraph.V) n match {
                      case pn @ PNode(0, _) =>
                        nodeMap ++= (pn -> recCallerNodes)

                      case pn @ PNode(i, _) =>
                        nodeMap ++= (pn -> argsCallerNodes(i-1))

                      case _ =>
                    }
                  case None =>
                    reporter.error("Could not find target function in funDecls for "+uniqueFunctionName(target)+" so I cannot assign args in the map", pos)
                }
            }

            def inlineINode(iNode: INode): INode = {
              // 1) we compose a new unique id
              val callId = uniqueID

              val newId = iNode.pPoint safeAdd callId

              // Like before, we check if the node was here
              val iNodeUnique    = INode(newId, true, iNode.types)
              val iNodeNotUnique = INode(newId, false, iNode.types)

              if (eCaller.ptGraph.V contains iNodeNotUnique) {
                iNodeNotUnique
              } else if (eCaller.ptGraph.V contains iNodeUnique) {
                newEnv = newEnv.replaceNode(iNodeUnique, Set(iNodeNotUnique))
                iNodeNotUnique
              } else {
                newEnv = newEnv.addNode(iNodeUnique)
                iNodeUnique
              }
            }

            // Map all inside nodes to themselves
            nodeMap +++= eCallee.ptGraph.vertices.toSeq.collect{ case n: INode => (n: Node,Set[Node](inlineINode(n))) }

            /*
            // Map all dangling calls to themselves
            nodeMap +++= eCallee.danglingCalls.toSeq.collect { case dc => (dc: Node, Set(dc: Node)) }
            */


            // Resolve load nodes
            def resolveLoadNode(lNode: LNode): Set[Node] = {
              val LNode(from, field, pPoint, types) = lNode

              val fromNodes = from match {
                case l : LNode =>
                  resolveLoadNode(l)
                case _ =>
                  nodeMap(from)
              }

              var pointedResults = Set[Node]()

              for (node <- fromNodes) {
                val writeTargets = newEnv.getWriteTargets(Set(node), field)

                val pointed = if (writeTargets.isEmpty) {
                  newEnv.getReadTargets(Set(node), field)
                } else {
                  writeTargets
                }

                if (pointed.isEmpty) {
                  val newId = pPoint safeAdd uniqueID

                  safeLNode(node, field, newId) match {
                    case Some(lNode) =>
                      newEnv = newEnv.addNode(lNode).addOEdge(node, field, lNode)
                      pointedResults += lNode
                    case None =>
                      // Ignore incompatibility
                  }
                } else {
                  pointedResults ++= pointed
                }
              }

              pointedResults
            }

            for (lNode <- gcCallee.loadNodes) {
              nodeMap ++= lNode -> resolveLoadNode(lNode)
            }

            newEnv = doFixPoint(gcCallee, newEnv, nodeMap)


            // Check for dangling calls that we could analyze now:
            /*
            for (dCall <- newEnv.danglingCalls) {
              val symbol    = dCall.symbol
              val recNodes  = dCall.obj flatMap nodeMap
              val argsNodes = dCall.args.map(_ flatMap nodeMap)
              val oset      = (ObjectSet.empty /: recNodes) (_ ++ _.types)
              val targets   = getMatchingMethods(dCall.symbol, oset.resolveTypes, pos, false)

              if (shouldInlineNow(symbol, oset, targets, true, None)) {
                val envs = for (target <- targets) yield {
                  // We need to replace the dCall node by retNodes
                  val (newEnvTmp, retNodes) = interProc(newEnv.copy(danglingCalls = newEnv.danglingCalls - dCall), target, recNodes, argsNodes, uniqueID, false, pos)

                  nodeMap -= dCall
                  nodeMap ++= dCall -> retNodes

                  newEnvTmp.replaceNode(dCall, retNodes)
                }

                newEnv = PointToLattice.join(envs toSeq : _*)
              } else {
                val newDCall = DCallNode(recNodes, argsNodes, symbol)
                if (dCall != newDCall) {
                  newEnv = newEnv.replaceNode(dCall, Set(newDCall))
                  nodeMap -= dCall
                  nodeMap += dCall -> newDCall
                }
              }
            }
            */
            (newEnv, gcCallee.rNodes flatMap nodeMap)
          } else {
            reporter.error("Unknown env for target "+target+" for call", pos)
            (eCaller, Set())
          }
        }

        st match {
          case av: CFG.AssignVal =>
            val (newEnv, nodes) = env.getNodes(av.v)
            env = newEnv.setL(av.r, nodes)

          case afr: CFG.AssignFieldRead =>
            val field = Field(afr.field)

            val (newEnv, fromNodes) = env.getNodes(afr.obj)

            env = newEnv.read(fromNodes, field, afr.r, afr.uniqueID)

          case afw: CFG.AssignFieldWrite =>
            val field = Field(afw.field)

            val (newEnv,  fromNodes) = env.getNodes(afw.obj)
            val (newEnv2, toNodes)   = newEnv.getNodes(afw.rhs)

            env = newEnv2.write(fromNodes, field, toNodes, true)

          case aam: CFG.AssignApplyMeth => // r = o.v(..args..)

            val (newEnv, nodes)   = env.getNodes(aam.obj)

            val oset = aam.obj match {
              case CFG.SuperRef(sym) =>
                ObjectSet.singleton(sym.superClass.tpe)
              case _ =>
                (ObjectSet.empty /: nodes) (_ ++ _.types)
            }

            val targets = getMatchingMethods(aam.meth, oset.resolveTypes, aam.pos, aam.isDynamic)

            checkIfInlinable(aam.meth, oset, targets) match {
              case None =>
                env = PointToLattice.join(targets map (sym => interProcByCall(env, sym, aam)) toSeq : _*)
              case Some(reason) =>
                aam.obj match {
                  case CFG.SuperRef(sym) =>
                    reporter.error("Cannot inline/delay call to super."+sym.name+" ("+uniqueFunctionName(sym)+") (reason: "+reason+"). Ignoring call.", aam.pos)
                    env = env.addGlobalNode().setL(aam.r, Set(GBNode))
                  case _ =>
                    reporter.error("Cannot inline/delay call "+aam+" (reason: "+reason+"), targets was: "+targets+" (resolved: "+oset.resolveTypes+"). Ignoring call.", aam.pos)
                    env = env.addGlobalNode().setL(aam.r, Set(GBNode))
                }
            }
          case an: CFG.AssignNew => // r = new A
            val iNodeUnique    = INode(an.uniqueID, true,  ObjectSet.singleton(an.tpe))
            val iNodeNotUnique = INode(an.uniqueID, false, ObjectSet.singleton(an.tpe))

            if (env.ptGraph.V contains iNodeNotUnique) {
              env = env.setL(an.r, Set(iNodeNotUnique))
            } else if (env.ptGraph.V contains iNodeUnique) {
              env = env.replaceNode(iNodeUnique, Set(iNodeNotUnique)).setL(an.r, Set(iNodeNotUnique))
            } else {
              env = env.addNode(iNodeUnique).setL(an.r, Set(iNodeUnique))
            }

          case ac: CFG.AssignCast =>
            val (newEnv, nodes) = env.getNodes(ac.rhs)
            env = newEnv.setL(ac.r, nodes)

          case _ =>
        }

        env
      }

    }

    def checkIfInlinable(symbol: Symbol, oset: ObjectSet, targets: Set[Symbol]): Option[String] = {
      if (!oset.isExhaustive && !settings.wholeCodeAnalysis) {
        Some("unbouded number of targets")
      } else if (targets.isEmpty) {
        Some("no target could be found")
      } else {
        val unanalyzable = targets.filter(t => getPTEnv(t).isEmpty)

        if (!unanalyzable.isEmpty) {
          Some("some targets are unanalyzable: "+unanalyzable.map(uniqueFunctionName(_)).mkString(", "))
        } else {
          None
        }
      }
    }

    def analyze(fun: AbsFunction) = {
      val cfg       = fun.cfg
      var baseEnv   = new PTEnv()

      settings.ifVerbose {
        reporter.info("Analyzing "+fun.uniqueName+"...")
      }

      if (settings.debugFunction(uniqueFunctionName(fun.symbol))) {
        settings.extensiveDebug = true
      }

      // 1) We add 'this' and argument nodes
      val thisNode = fun.pointToArgs(0)

      baseEnv = baseEnv.addNode(thisNode).setL(cfg.mainThisRef, Set(thisNode))

      for (sr <- cfg.superRefs) {
        baseEnv = baseEnv.setL(sr, Set(thisNode))
      }

      for ((a, i) <- fun.CFGArgs.zipWithIndex) {
        val pNode = fun.pointToArgs(i+1)
        baseEnv = baseEnv.addNode(pNode).setL(a, Set(pNode))
      }

      // 2) If we are in the constructor, we assign all fields defined by this class to their default value
      if (fun.symbol.name == nme.CONSTRUCTOR) {
        for (d <- fun.symbol.owner.tpe.decls if d.isValue && !d.isMethod) {
          val node = typeToLitNode(d.tpe)

          baseEnv = baseEnv.addNode(node).addIEdges(Set(thisNode), Field(d), Set(node))
        }
      }

      // 3) We add all object nodes
      for(obref <- cfg.objectRefs) {
        val n = OBNode(obref.symbol)
        baseEnv = baseEnv.addNode(n).setL(obref, Set(n))
      }

      // 3) We run a fix-point on the CFG
      val ttf = new PointToTF(fun)
      val aa = new dataflow.Analysis[PTEnv, CFG.Statement](PointToLattice, baseEnv, settings)

      aa.computeFixpoint(cfg, ttf)

      // 4) We retrieve the result at exit
      val res = aa.getResult

      fun.pointToInfos  = res

      val e = res(cfg.exit).setReturnNodes(cfg.retval)

      fun.pointToResult = e.stripTypeInconsistencies

      settings.ifVerbose {
        reporter.info("Done analyzing "+fun.uniqueName+"...")
      }

      if (settings.fillGraphs && settings.fillGraphsIteratively) {
        fillPartial(fun)
      }

      settings.extensiveDebug = false

      res
    }

    def analyzeSCC(scc: Set[Symbol]) {
      // The analysis is only run on symbols that are actually AbsFunctions, not all method symbols

      var workList = scc

      // 1) First, we remove from the worklist functions that we cannot analyze
      for (sym <- scc if !(funDecls contains sym)) {
        if (getPTEnv(sym).isEmpty) {
          reporter.warn("Ignoring the analysis of unknown methods: "+uniqueFunctionName(sym))
        }
        workList -= sym
      }

      // 2) Then, we analyze every methods until we reach a fixpoint
      while(!workList.isEmpty) {
        val sym = workList.head
        workList = workList.tail

        if (funDecls contains sym) {
          val fun = funDecls(sym)

          val eBefore  = fun.pointToResult

          analyze(fun)

          val eAfter   = fun.pointToResult

          if (eBefore != eAfter) {
            workList ++= (simpleReverseCallGraph(sym) & scc)
          }
        }
      }
    }

    def abstractsClassAnnotation(symbol: Symbol): Option[Symbol] = {
      symbol.annotations.find(_.atp.safeToString startsWith "insane.annotations.Abstracts") match {
          case Some(annot) =>

            annot.args match {
              case List(l: Literal) =>
                val name = l.value.stringValue

                try {
                  annot.atp.safeToString match {
                    case "insane.annotations.AbstractsClass" =>
                      Some(definitions.getClass(name))
                    case "insane.annotations.AbstractsModuleClass" =>
                      Some(definitions.getModule(name).moduleClass)
                    case _ =>
                      reporter.error("Could not understand annotation: "+annot, symbol.pos)
                      None
                  }
                } catch {
                  case e =>
                    reporter.error("Unable to find class symbol from name "+name+": "+e.getMessage)
                    None
                }
              case _ =>
                reporter.error("Could not understand annotation: "+annot, symbol.pos)
                None
            }
          case None =>
            None
        }
    }

    def abstractsMethodAnnotation(symbol: Symbol): Option[String] = {
      symbol.annotations.find(_.atp.safeToString == "insane.annotations.AbstractsMethod") match {
          case Some(annot) =>

            annot.args match {
              case List(l: Literal) => Some(l.value.stringValue)
              case _ =>
                reporter.error("Could not understand annotation: "+annot, symbol.pos)
                None
            }
          case None =>
            None
        }
    }

    def getResultEnv(fun: AbsFunction): (String, PTEnv, Boolean) = {
      // We get the name of the method in the annotation, if any
      var isSynth = false

      var env = fun.pointToResult

      var name = abstractsMethodAnnotation(fun.symbol) match {
        case Some(n) =>
          isSynth = true

          if (fun.body == EmptyTree) {
            // In case the function was abstract with a method annotation, we
            // generate its return value node

            val iNode = INode(new UniqueID(0), true, methodReturnType(fun.symbol))
            env = env.addNode(iNode).copy(rNodes = Set(iNode))
            fun.pointToResult = env
          }
          n
        case None =>
          uniqueFunctionName(fun.symbol)
      }

      // We check if the class actually contains the Abstract annotation, in
      // which case we need to fix types of nodes.
      abstractsClassAnnotation(fun.symbol.owner) match {
        case Some(newClass) =>
          val oldClass = fun.symbol.owner
          // We need to replace references to fun.symbol.owner into symbol
          env = new PTEnvReplacer(Map(oldClass.tpe -> newClass.tpe), Map(oldClass -> newClass)).copy(env)
          isSynth = true

        case None =>
      }

      (name, env, isSynth)
    }

    def fillDatabase() {
      if (Database.active) {
        reporter.info("Inserting "+funDecls.size+" graph entries in the database...")

        val toInsert = for ((s, fun) <- funDecls) yield {

          val (name, e, isSynth) = getResultEnv(fun)

          (name, new EnvSerializer(e).serialize(), isSynth)
        }

        Database.Env.insertAll(toInsert)
      } else {
        reporter.error("Cannot insert into database: No database configuration")
      }
    }

    def fillPartial(fun: AbsFunction) {
      if (Database.active) {
        val (name, e, isSynth) = getResultEnv(fun)

        val toInsert = List((name, new EnvSerializer(e).serialize(), isSynth))

        Database.Env.insertAll(toInsert)
      } else {
        reporter.error("Cannot insert into database: No database configuration")
      }
    }

    def run() {
      // 1) Define symbols that have no chance of ever being implemented/defined
      predefinedEnvs += definitions.getMember(definitions.ObjectClass, nme.CONSTRUCTOR) -> Some(BottomPTEnv)

      // 1.5) Check symbols that will not be able to be analyzed:
      settings.ifDebug {
        for (scc <- callGraphSCCs.map(scc => scc.vertices.map(v => v.symbol)); sym <- scc) {
          if (!(funDecls contains sym)) {
            reporter.warn("No code for method: "+uniqueFunctionName(sym))
          }
        }
      }

      // 2) Analyze each SCC in sequence, in the reverse order of their topological order
      //    We first analyze {M,..}, and then methods that calls {M,...}
      val workList = callGraphSCCs.reverse.map(scc => scc.vertices.map(v => v.symbol))
      for (scc <- workList) {
        analyzeSCC(scc)
      }

      // 3) Fill graphs in the DB, if asked to
      if (settings.fillGraphs && !settings.fillGraphsIteratively) {
        fillDatabase()
      }

      // 4) Display/dump results, if asked to
      if (!settings.dumpptgraphs.isEmpty) {
        for ((s, fun) <- funDecls if settings.dumpPTGraph(safeFullName(s))) {

          val (name, e, _) = getResultEnv(fun)
          val cfg  = fun.cfg

          var newGraph = e.ptGraph

          // We complete the graph with local vars -> nodes association, for clarity
          for ((ref, nodes) <- e.locState if ref != cfg.retval; n <- nodes) {
            newGraph += VEdge(VNode(ref), n)
          }

          /*
          // We also add Dangling call information
          for (dCall <- e.danglingCalls) {
            newGraph += dCall

            for (node <- dCall.obj) {
              newGraph += DCallObjEdge(node, dCall)
            }

            for ((argNodes, i) <- dCall.args.zipWithIndex; node <- argNodes) {
              newGraph += DCallArgEdge(node, i, dCall)
            }
          }
          */

          val dest = name+"-pt.dot"

          reporter.info("Dumping Point-To Graph to "+dest+"...")
          new PTDotConverter(newGraph, "Point-to: "+name, e.rNodes).writeFile(dest)
        }
      }

      settings.drawpt match {
        case Some(name) =>
          if (Database.active) {
            Database.Env.lookupEnv(name).map(s => EnvUnSerializer(s).unserialize) match {
              case Some(e) =>
                val dest = name+"-pt.dot"

                reporter.info("Dumping Point-To Graph to "+dest+"...")
                new PTDotConverter(e.ptGraph, "Point-to: "+name, e.rNodes).writeFile(dest)
              case None =>
                reporter.error("Could not find "+name+" in database!")
            }
          } else {
            reporter.error("Could not find "+name+" in database: No database connection!")
          }
        case None =>
      }

      if (!settings.displaypure.isEmpty) {
        reporter.title(" Purity Results:")
        for ((s, fun) <- funDecls if settings.displayPure(safeFullName(s))) {

          val (name, e, _) = getResultEnv(fun)

          val modClause = e.modifiesClause

          val modClauseString = if (modClause.isPure) {
            "@Pure"
          } else {
            def nodeToString(n: Node) = n match {
              case PNode(0, _) =>
                "this"
              case PNode(i, _) =>
                fun.args(i-1).symbol.name.toString
              case OBNode(s) =>
                s.name.toString
              case _ =>
                n.name
            }
            "@Modifies"+modClause.effects.map(e => nodeToString(e.root).trim+"."+e.chain.map(_.name.trim).mkString(".")).mkString("(", ", ",")")
          }

          reporter.info(String.format("  %-40s: %s", fun.symbol.fullName, modClauseString))
        }
      }
    }
  }
}

