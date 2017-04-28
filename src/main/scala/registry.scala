import hyperion._

package hyperion {
    case class NodeProperty(id : String, left: Int, top: Int,  content: PipeOptions)
    case class Connection(from: String, to : String)
    case class Config(nodes : List[NodeProperty], connections : List[Connection])

    object Registry {
        val nodes : scala.collection.mutable.Map[String, NodeProperty] = scala.collection.mutable.Map[String,NodeProperty]();
        val connections : scala.collection.mutable.MutableList[Connection] = scala.collection.mutable.MutableList[Connection]();
        def add(node : NodeProperty) = nodes.update(node.id, node)

        def hasNode(id: String) = nodes.contains(id)

        def connect(from: String, to: String) = connections += Connection(from, to)

        def hasConnection(connection: Connection) = connections.contains(connection)

        def getNodesAsList = nodes.values.toList

        def config = Config(getNodesAsList, connections.toList)
    }
}
