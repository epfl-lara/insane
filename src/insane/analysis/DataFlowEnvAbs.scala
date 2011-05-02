package insane
package analysis

import CFG.CFGVertex

trait DataFlowEnvAbs[E <: DataFlowEnvAbs[_, S], S] {
    type Env = E
    type Vertex = CFGVertex[S]

    def union (env: E): E;
    def copy: E;
}
