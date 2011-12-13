package insane
package alias

import storage.Database

import utils._
import utils.Reporters._
import utils.Graphs.DotConverter
import CFG._

import scala.reflect.generic.Flags

trait PointToAnalysis extends PointToGraphsDefs with PointToEnvs with PointToLattices {
  self: AnalysisComponent =>

  import global._
  import PointToGraphs._

  var cnt = 0

  //var predefinedPriorityEnvs = Map[Symbol, Option[PTEnv]]()

  val ptProgressBar = reporter.getProgressBar(42);

  //def getPredefPriorityEnv(sym: Symbol): Option[PTEnv] = predefinedPriorityEnvs.get(sym) match {
  //  case Some(optPTEnv) => optPTEnv
  //  case None =>
  //    if (Database.active) {
  //      val optEnv = Database.Env.lookupPriorityEnv(uniqueFunctionName(sym)).map(s => EnvUnSerializer(s).unserialize)
  //      predefinedPriorityEnvs += sym -> optEnv

  //      optEnv
  //    } else {
  //      None
  //    }
  //}

  //var predefinedEnvs = Map[Symbol, Option[PTEnv]]()

  //def getPredefEnv(sym: Symbol): Option[PTEnv] = predefinedEnvs.get(sym) match {
  //  case Some(optPTEnv) => optPTEnv
  //  case None =>
  //    if (Database.active) {
  //      val optEnv = Database.Env.lookupEnv(uniqueFunctionName(sym)).map(s => EnvUnSerializer(s).unserialize)
  //      predefinedEnvs += sym -> optEnv
  //      optEnv
  //    } else {
  //      None
  //    }
  //}

  //def getPTEnv(sym: Symbol): Option[PTEnv] = {
  //  getPredefPriorityEnv(sym) orElse getPTEnvFromFunSym(sym) orElse getPredefEnv(sym)
  //}

  var predefinedStaticCFGs = Map[Symbol, Option[FunctionCFG]]()
  def getPredefStaticCFG(sym: Symbol) = predefinedStaticCFGs.get(sym) match {
    case Some(optcfg) =>
      optcfg

    case None =>
      val optcfg = uniqueFunctionName(sym) match {
        case "java.lang.Object.<init>(()java.lang.Object)" |
             "java.lang.Object.$eq$eq((x$1: java.lang.Object)Boolean)" |
             "java.lang.Object.$bang$eq((x$1: java.lang.Object)Boolean)" =>

          val (args, retval) = sym.tpe match {
            case MethodType(argssym, tpe) =>
              (argssym.map(s => new CFGTrees.SymRef(s, 0)), new CFGTrees.TempRef("retval", 0, tpe))

            case tpe =>
              (Seq(), new CFGTrees.TempRef("retval", 0, tpe))
          }

          var staticCFG = new FunctionCFG(sym, args, retval, true)
          staticCFG += (staticCFG.entry, CFGTrees.Skip, staticCFG.exit)
          Some(staticCFG)

        case _ =>
          None
      }

      predefinedStaticCFGs += sym -> optcfg

      optcfg
  }

  class PointToAnalysisPhase extends SubPhase {
    val name = "Point-to Analysis"

    object PTAnalysisModes extends Enumeration {
      val PreciseAnalysis = Value("PreciseAnalysis")
      val BluntAnalysis   = Value("BluntAnalysis")
    }

    type AnalysisMode = PTAnalysisModes.Value
    import PTAnalysisModes._


    var calculatedEffects = Map[Symbol, FunctionCFG]()

    def getPTCFGFromFun(fun: AbsFunction): FunctionCFG = {
      getPTCFGFromFun(fun, declaredArgsTypes(fun))
    }

    def getPTCFGFromFun(fun: AbsFunction, argsTypes: Seq[ObjectSet]): FunctionCFG = {
      // Is the PTCFG for this signature already ready?
      fun.ptCFGs.get(argsTypes) match {
        case Some(ptCFG) =>
          // Yes.
          ptCFG
        case None =>
          // No, we prepare a fresh one given the types, and store it.
          val cfg = preparePTCFG(fun, argsTypes)
          fun.ptCFGs += argsTypes -> cfg
          cfg
      }
    }

    def getPTCFG(sym: Symbol, argsTypes: Seq[ObjectSet]): Option[FunctionCFG] = {
      funDecls.get(sym) match {
        case Some(fun) =>
          Some(getPTCFGFromFun(fun, argsTypes))
        case None =>
          getPredefStaticCFG(sym)
      }
    }

    class PointToTF(fun: AbsFunction, callGraphSCC: Set[Symbol], analysisMode: AnalysisMode) extends dataflow.TransferFunctionAbs[PTEnv, CFG.Statement] {

      var analysis: dataflow.Analysis[PTEnv, CFG.Statement, FunctionCFG] = null

      def apply(edge: CFGEdge[CFG.Statement], oldEnv: PTEnv, scc: SCC[CFGVertex]): PTEnv = {
        val st  = edge.label

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

        def mergeGraphs(outerG: PTEnv, innerG: PTEnv, uniqueID: UniqueID, pos: Position, allowStrongUpdates: Boolean): PTEnv = {

          if (outerG.isBottom) {
            innerG
          } else {

            cnt += 1
            settings.ifDebug {
              if (settings.dumpPTGraph(safeFullName(fun.symbol))) {
                reporter.debug("    Merging graphs ("+cnt+")...")
                new PTDotConverter(outerG, "Before - "+cnt).writeFile("before-"+cnt+".dot")
                new PTDotConverter(innerG, "Inner - "+cnt).writeFile("inner-"+cnt+".dot")
              }
            }

            // Build map
            var newOuterG = outerG;

            // 1) We build basic nodemap

            var nodeMap: NodeMap = NodeMap()
            for (n <- GBNode :: NNode :: NNode :: BooleanLitNode :: LongLitNode :: DoubleLitNode :: StringLitNode :: IntLitNode :: ByteLitNode :: CharLitNode :: FloatLitNode :: ShortLitNode :: Nil if innerG.ptGraph.V.contains(n)) {
              nodeMap += n -> n
            }

            // 2) We add all singleton object nodes to themselves
            for (n <- innerG.ptGraph.V.filter(_.isInstanceOf[OBNode])) {
              nodeMap += n -> n
            }

            // 3) We map local variables-nodes to the corresponding outer ones
            for (n <- innerG.ptGraph.V.filter(_.isInstanceOf[LVNode])) {
              val ref = n.asInstanceOf[LVNode].ref

              val (newEnv, nodes) = newOuterG.getL(ref, false);
              newOuterG = newEnv

              nodeMap ++= (n -> nodes)
            }

            // 4) Inline Inside nodes with refinement of the allocation site
            def inlineINode(iNode: INode): INode = {
              // 1) we compose a new unique id
              val callId = uniqueID

              val newId = iNode.pPoint safeAdd callId

              // Like before, we check if the node was here
              val iNodeUnique    = INode(newId, true, iNode.types)
              val iNodeNotUnique = INode(newId, false, iNode.types)

              if (newOuterG.ptGraph.V contains iNodeNotUnique) {
                iNodeNotUnique
              } else if (newOuterG.ptGraph.V contains iNodeUnique) {
                newOuterG = newOuterG.replaceNode(iNodeUnique, Set(iNodeNotUnique))
                iNodeNotUnique
              } else {
                newOuterG = newOuterG.addNode(iNodeUnique)
                iNodeUnique
              }
            }

            // Map all inside nodes to themselves
            nodeMap +++= innerG.ptGraph.vertices.toSeq.collect{ case n: INode => (n: Node,Set[Node](inlineINode(n))) }

            // 5) Resolve load nodes
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
                val writeTargets = newOuterG.getWriteTargets(Set(node), field)

                val pointed = if (writeTargets.isEmpty) {
                  newOuterG.getReadTargets(Set(node), field)
                } else {
                  writeTargets
                }

                if (pointed.isEmpty) {
                  val newId = pPoint safeAdd uniqueID

                  safeLNode(node, field, newId) match {
                    case Some(lNode) =>
                      newOuterG = newOuterG.addNode(lNode).addOEdge(node, field, lNode)
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

            for (lNode <- innerG.loadNodes) {
              nodeMap ++= lNode -> resolveLoadNode(lNode)
            }

            // 6) Apply inner edges
            def applyInnerEdgesFixPoint(envInner: PTEnv, envInit: PTEnv, nodeMap: NodeMap): PTEnv = {
              var env  = envInit
              var lastEnv  = env

              do {
                lastEnv  = env

                // We map all edges to their new nodes potentially creating more or less edges
                val mappedEdges = for (IEdge(v1, field, v2) <- envInner.iEdges; mappedV1 <- nodeMap(v1); mappedV2 <- nodeMap(v2)) yield (IEdge(mappedV1, field, mappedV2), v1)

                for (((newV1, field), edgesOldV1) <- mappedEdges.groupBy { case (edge, oldV1) => (edge.v1, edge.label) }) {
                  val (edges, oldV1s) = edgesOldV1.unzip

                  // We only allow strong updates if newV1 was the only target of oldV1
                  val allowStrong = allowStrongUpdates && oldV1s.forall { nodeMap(_).size == 1 }

                  env = env.write(Set(newV1), field, edges.map(_.v2), allowStrong)
                }
              } while (lastEnv != env)

              env
            }

            settings.ifDebug {
              if (settings.dumpPTGraph(safeFullName(fun.symbol))) {
                new PTDotConverter(newOuterG, "Inter - "+cnt).writeFile("inter-"+cnt+".dot")
              }
            }

            newOuterG = applyInnerEdgesFixPoint(innerG, newOuterG, nodeMap)

            // 7) Overwrites of local variables need to be taken into account
            for ((r, nodes) <- innerG.locState) {
              newOuterG = newOuterG.setL(r, nodes flatMap nodeMap)
            }

            if (settings.dumpPTGraph(safeFullName(fun.symbol))) {
              new PTDotConverter(newOuterG, "new - "+cnt).writeFile("new-"+cnt+".dot")
            }

            newOuterG
          }
        }

        st match {
          case ef: CFG.Effect =>
            env = mergeGraphs(env, ef.env, ef.uniqueID, ef.pos, true)

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

            var (newEnv, nodes)   = env.getNodes(aam.obj)

            val name = uniqueFunctionName(fun.symbol);

            /**
             * We define two types of inlining:
             *  1) Inlining by CFG (Precise)
             *      When possible, we inline the CFG of the target method, or
             *      partial effect summary into the current CFG. This is
             *      generally not possible if there are mutually recursive
             *      functions.
             *  2) Inlining by Effects (Blunt)
             *      When inlining CFGs is not possible, we inline the effects
             *      directly. A definite effect, often imprecise, is computed.
             */
            val oset = aam.obj match {
              case CFG.SuperRef(sym, _) =>
                ObjectSet.singleton(sym.superClass.tpe)
              case _ =>
                (ObjectSet.empty /: nodes) (_ ++ _.types)
            }

            val callArgsTypes = for (a <- aam.args) yield {
              val (tmp, nodes) = newEnv.getNodes(a)
              newEnv = tmp
              (ObjectSet.empty /: nodes) (_ ++ _.types)
            }


            val callTypes = oset +: callArgsTypes

            /*
             * If we are in a loop, the types computed using the nodes is
             * generally incorrect, we need to augment it using the types
             * computed statically during type analysis
             */
            def shouldWeInlineThis(symbol: Symbol, oset: ObjectSet, targets: Set[Symbol]): Either[Boolean, (String, Boolean)] = {
              if (targets.isEmpty) {
                Right("no target could be found", true)
              } else {
                analysisMode match {
                  case PreciseAnalysis =>
                    if (!oset.isExhaustive && !settings.wholeCodeAnalysis) {
                      Right("unbouded number of targets", true)
                    } else {
                      if (targets.size > 3) {
                        Right("too many targets ("+targets.size+")", false)
                      } else {
                        val unanalyzable = targets.filter(t => getPTCFG(t, callArgsTypes).isEmpty)

                        if (!unanalyzable.isEmpty) {
                          Right("some targets are unanalyzable: "+unanalyzable.map(uniqueFunctionName(_)).mkString(", "), true)
                        } else {
                          Left(true)
                        }
                      }
                    }
                  case BluntAnalysis =>
                    // We have to analyze this, and thus inline, no choice here.
                    Left(true)
                }
              }
            }

            val targets = getMatchingMethods(aam.meth, oset.resolveTypes, aam.pos, aam.isDynamic)

            shouldWeInlineThis(aam.meth, oset, targets) match {
              case Left(true) => // We should
                // 1) Gather CFGs of targets
                val existingTargetsCFGs = targets flatMap { sym =>
                 if (callGraphSCC contains sym) {
                  // For methods in the same callgraph-SCC (i.e. mutually
                  // dependant), we have to get a fully-reduced effect graph
                  calculatedEffects.get(sym) match {
                    case Some(ptcfg) =>
                      assert(ptcfg.isFullyReduced, "PT-CFG obtained by inter-dependant call is not fully reduced!")

                      Some(ptcfg)
                    case None =>
                      None
                  }
                 } else {
                  // For other methods we should have a PTCFG ready to be used
                  getPTCFG(sym, callTypes) match {
                    case None =>
                      reporter.error("Could not gather pt-CFG of "+sym.name+" ("+uniqueFunctionName(sym)+"), ignoring.")
                      None
                    case cfg =>
                      cfg
                  }

                 }

                }

                var cfg = analysis.cfg

                settings.ifDebug {
                  reporter.debug("  Ready to inline for : "+aam+", "+existingTargetsCFGs.size+" targets available")
                }

                val nodeA = edge.v1
                val nodeB = edge.v2

                // 2) Remove current edge
                cfg -= edge

                if (existingTargetsCFGs.size == 0) {
                    // We still want to be able to reach nodeB
                    cfg += (nodeA, CFG.Skip, nodeB)
                }

                /**
                 * We replace
                 *   nodeA -- r = call(arg1,...argN) -- nodeB
                 * into:
                 *   nodeA -- arg1=Farg1 -- ... argN--FargN -- rename(CFG of Call) -- r = retval -- nodeB
                 */

                for (targetCFG <- existingTargetsCFGs) {

                  var map = Map[CFGTrees.Ref, CFGTrees.Ref]()

                  var connectingEdges = Set[CFG.Statement]()

                  // 1) Build renaming map:
                  //  a) mapping args
                  for ((callArg, funArg) <- aam.args zip targetCFG.args) {
                    callArg match {
                      case r: CFGTrees.Ref =>
                        map += funArg -> r
                      case _ =>
                        // Mapping simple values is not possible, we map by assigning
                        connectingEdges += new CFG.AssignVal(funArg, callArg)
                    }
                  }

                  // b) mapping receiver
                  aam.obj match {
                      case r: CFGTrees.Ref =>
                        map += targetCFG.mainThisRef -> r
                      case _ =>
                        reporter.error("Unnexpected non-ref for the receiver!", aam.pos)
                  }

                  // c) mapping retval
                  map += targetCFG.retval -> aam.r

                  // 2) Rename targetCFG
                  val renamedCFG = new FunctionCFGRefRenamer(map).copy(targetCFG)

                  // 3) Connect renamedCFG to the current CFG
                  if (connectingEdges.isEmpty) {
                    // If no arg was explicitely mapped via assigns, we still need to connect to the CFG
                    cfg += (nodeA, CFG.Skip, renamedCFG.entry)
                  } else {
                    for(stmt <- connectingEdges) {
                      cfg += (nodeA, stmt, renamedCFG.entry)
                    }
                  }

                  // 4) Adding CFG Edges
                  for (tEdge <- renamedCFG.graph.E) {
                    cfg += tEdge
                  }

                  // 5) Retval has been mapped via renaming, simply connect it
                  cfg += (renamedCFG.exit, CFG.Skip, nodeB)
                }

                settings.ifDebug {
                  reporter.debug("  Restarting...")
                }

                cnt += 1

                cfg = cfg.removeSkips.removeIsolatedVertices
                if (settings.dumpPTGraph(safeFullName(fun.symbol))) {
                  new CFGDotConverter(cfg, "work").writeFile(uniqueFunctionName(fun.symbol)+"-work.dot")
                }

                analysis.restartWithCFG(cfg)

              case Right((reason, isError)) =>
                aam.obj match {
                  case CFG.SuperRef(sym, _) =>
                    reporter.error(List(
                      "Cannot inline/delay call to super."+sym.name+" ("+uniqueFunctionName(sym)+"), ignoring call.",
                      "Reason: "+reason), aam.pos)

                    // From there on, the effects are partial graphs
                    env = new PTEnv(true, false)
                  case _ =>
                    if (isError) {
                      reporter.error(List("Cannot inline/delay call "+aam+", ignoring call.",
                        "Reason: "+reason), aam.pos)
                    } else {
                      settings.ifDebug {
                        reporter.debug(List("Delaying call to "+aam+"",
                          "Reason: "+reason), aam.pos)
                      }
                    }

                    // From there on, the effects are partial graphs
                    env = new PTEnv(true, false)
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

    def preparePTCFG(fun: AbsFunction, argsTypes: Seq[ObjectSet]): FunctionCFG = {
        var cfg        = fun.cfg
        var baseEnv    = new PTEnv()

        // 1) We add 'this'/'super'
        val thisNode = LVNode(cfg.mainThisRef, argsTypes.head)
        baseEnv = baseEnv.addNode(thisNode).setL(cfg.mainThisRef, Set(thisNode))

        for (sr <- cfg.superRefs) {
          baseEnv = baseEnv.setL(sr, Set(thisNode))
        }

        // 2) We add arguments
        for ((a, oset) <- cfg.args zip argsTypes.tail) {
          val aNode = if (isGroundOSET(oset)) {
              typeToLitNode(oset.exactTypes.head)
            } else {
              LVNode(a, oset)
            }
          baseEnv = baseEnv.addNode(aNode).setL(a, Set(aNode))
        }

        // 3) If we are in the constructor, we assign all fields defined by this class to their default value
        if (fun.symbol.name == nme.CONSTRUCTOR) {
          for (d <- fun.symbol.owner.tpe.decls if d.isValue && !d.isMethod) {
            val node = typeToLitNode(d.tpe)

            baseEnv = baseEnv.addNode(node).addIEdges(Set(thisNode), Field(d), Set(node))
          }
        }

        // 4) We add all object nodes
        for(obref <- cfg.objectRefs) {
          val n = OBNode(obref.symbol)
          baseEnv = baseEnv.addNode(n).setL(obref, Set(n))
        }


        // 5) We alter the CFG to put a bootstrapping graph step
        val bstr = cfg.newNamedVertex("bootstrap")

        for (e @ CFGEdge(_, l, v2) <- cfg.graph.outEdges(cfg.entry)) {
          cfg += CFGEdge(bstr, l, v2)
          cfg -= e
        }

        cfg += CFGEdge(cfg.entry, new CFGTrees.Effect(baseEnv, "Bootstrap of "+uniqueFunctionName(fun.symbol)) setTree fun.body, bstr)

        cfg
    }

    def analyzePTCFG(fun: AbsFunction, callGraphSCC: Set[Symbol], mode: AnalysisMode, cfg: FunctionCFG): FunctionCFG = {
      settings.ifVerbose {
        reporter.msg("Analyzing "+fun.uniqueName+"...")
      }

      // We run a fix-point on the CFG
      val ttf = new PointToTF(fun, callGraphSCC, mode)
      val aa = new dataflow.Analysis[PTEnv, CFG.Statement, FunctionCFG](PointToLattice, PointToLattice.bottom, settings, cfg)

      ttf.analysis = aa

      aa.computeFixpoint(ttf)

      // Analysis CFG might have expanded
      val newCFG = aa.cfg
      val res    = aa.getResult
      val e      = res(newCFG.exit)

      settings.ifVerbose {
        reporter.debug("  Done analyzing "+fun.uniqueName+"...")
      }


      var reducedCFG = new FunctionCFG(fun.symbol, newCFG.args, newCFG.retval, true)

      reducedCFG += (reducedCFG.entry, new CFGTrees.Effect(e.cleanLocState(reducedCFG).cleanUnreachable(reducedCFG), "Sum: "+uniqueFunctionName(fun.symbol)) setTree fun.body, reducedCFG.exit)

      calculatedEffects += fun.symbol -> reducedCFG

      // We reduce the result
      if (e.isPartial && mode == PreciseAnalysis) {
        // TODO: partial reduce
        println("Partial result is: "+newCFG)
        newCFG
      } else {
        println("Reduced result is: "+reducedCFG)
        reducedCFG
      }
    }

    def declaredArgsTypes(fun: AbsFunction): Seq[ObjectSet] = {
     ObjectSet.subtypesOf(fun.symbol.owner.tpe) +: fun.args.map(a => ObjectSet.subtypesOf(a.tpe));
    }

    def analyze(fun: AbsFunction, callgraphSCC: Set[Symbol], mode: AnalysisMode = PreciseAnalysis) = {
      val resultPTCFG = specializedAnalyze(fun, callgraphSCC, mode, declaredArgsTypes(fun))


      resultPTCFG
    }

    def specializedAnalyze(fun: AbsFunction, callgraphSCC: Set[Symbol], mode: AnalysisMode, argsTypes: Seq[ObjectSet]) = {
      val result = analyzePTCFG(fun, callgraphSCC, mode, getPTCFGFromFun(fun, argsTypes))

      fun.ptCFGs += argsTypes -> result

      if (settings.dumpPTGraph(safeFullName(fun.symbol))) {
        val name = uniqueFunctionName(fun.symbol)
        val dest = name+"-ptcfg.dot"

        reporter.info("  Dumping pt-CFG to "+dest+"...")
        new CFGDotConverter(result, "pt-CFG For "+name).writeFile(dest)
      }

      result
    }

    def analyzeSCC(scc: Set[Symbol]) {
      // The analysis is only run on symbols that are actually AbsFunctions, not all method symbols

      var workList = scc

      // 1) First, we remove from the worklist functions that we cannot analyze
      for (sym <- scc if !(funDecls contains sym)) {
        workList -= sym
      }

      // 2) Then, we analyze every methods until we reach a fixpoint
      while(!workList.isEmpty) {
        val sym = workList.head
        workList = workList.tail

        ptProgressBar.draw()

        if (funDecls contains sym) {
          val fun = funDecls(sym)

          val cfgBefore  = getPTCFGFromFun(fun)

          analyze(fun, scc)

          val cfgAfter   = getPTCFGFromFun(fun)

          if (cfgBefore != cfgAfter) {
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

    /*
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

            //val iNode = INode(new UniqueID(0), true, methodReturnType(fun.symbol))
            //env = env.addNode(iNode).copy(rNodes = Set(iNode))
            //fun.pointToResult = env
            sys.error("TODO")
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
    */

    /*
    def fillDatabase() {
      if (Database.active) {
        reporter.msg("Inserting "+funDecls.size+" graph entries in the database...")

        val toInsert = for ((s, fun) <- funDecls) yield {

          val (name, e, isSynth) = getResultEnv(fun)

          (name, new EnvSerializer(e).serialize(), isSynth)
        }

        Database.Env.insertAll(toInsert)
      } else {
        reporter.error("Cannot insert into database: No database configuration")
      }
    }
    */

    /*
    def fillPartial(fun: AbsFunction) {
      if (Database.active) {
        val (name, e, isSynth) = getResultEnv(fun)

        val toInsert = List((name, new EnvSerializer(e).serialize(), isSynth))

        Database.Env.insertAll(toInsert)
      } else {
        reporter.error("Cannot insert into database: No database configuration")
      }
    }
    */

    def run() {
      // 1) Analyze each SCC in sequence, in the reverse order of their topological order
      //    We first analyze {M,..}, and then methods that calls {M,...}
      val workList = callGraphSCCs.reverse.map(scc => scc.vertices.map(v => v.symbol))
      val totJob   = workList.map(_.size).sum

      ptProgressBar.setMax(totJob)
      ptProgressBar.draw()

      for (scc <- workList) {
        analyzeSCC(scc)
        ptProgressBar ticks scc.size
        ptProgressBar.draw()
      }

      ptProgressBar.end();

      // 2) Fill graphs in the DB, if asked to
      //if (settings.fillGraphs && !settings.fillGraphsIteratively) {
      //  fillDatabase()
      //}

      // 4) Display/dump results, if asked to
      if (!settings.dumpptgraphs.isEmpty) {
        for ((s, fun) <- funDecls if settings.dumpPTGraph(safeFullName(s))) {
          val ptCFG = getPTCFGFromFun(fun)
          val name = uniqueFunctionName(fun.symbol)
          val dest = name+"-ptcfg.dot"
          reporter.msg("Dumping Point-To-CFG Graph to "+dest+"...")
          new CFGDotConverter(ptCFG, "Point-to-CFG: "+name).writeFile(dest)
        }
      }

      settings.drawpt match {
        case Some(name) =>
          if (Database.active) {
            Database.Env.lookupEnv(name).map(s => EnvUnSerializer(s).unserialize) match {
              case Some(e) =>
                val dest = name+"-pt.dot"

                reporter.msg("Dumping Point-To Graph to "+dest+"...")
                new PTDotConverter(e, "Point-to: "+name).writeFile(dest)
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

          /*
          val (name, e, _) = getResultEnv(fun)

          val modClause = e.modifiesClause

          val modClauseString = if (modClause.isPure) {
            "@Pure"
          } else {
            def nodeToString(n: Node) = n match {
              case OBNode(s) =>
                s.name.toString
              case _ =>
                n.name
            }
            "@Modifies"+modClause.effects.map(e => nodeToString(e.root).trim+"."+e.chain.map(_.name.trim).mkString(".")).mkString("(", ", ",")")
          }

          reporter.msg(String.format("  %-40s: %s", fun.symbol.fullName, modClauseString))
          */
        }
      }
    }
  }
}

