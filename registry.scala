import hyperion._

package hyperion {
    case class NodeProperty(id : String, left: Int, top: Int,  content: PipeOptions)

    object Registry {
        val nodes : scala.collection.mutable.Map[String, NodeProperty] = scala.collection.mutable.Map[String,NodeProperty]();
        def add(node : NodeProperty) = nodes.update(node.id, node)

        def getNodesAsList = nodes.values.toList
    }
}
