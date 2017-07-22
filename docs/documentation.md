Hyperion
========

Hyperion is an Akka-based logging server written in Scala. The processing nodes are individual actors which are
connected to each other. Message loss guarantees currently are the same what Akka guarantees.

Development
===========

Prerequisite: java8

* Install sbt from either http://www.scala-sbt.org/ or package manager.
* Check out hyperion

Sbt commands:
* ``sbt run`` : starts hyperion server
* ``sbt test`` : runs test suite
* ``sbt assembly``: creates "fat jar" which has only JVM as a dependency
* ``sbt clean coverage test``: runs test suite with coverage calculation

API
===

After starting, hyperion will listen on 0.0.0.0:8080. Reaching / will redirect to the frontend.
The API endpoints be be reached under /api/

/api/config
-----------

* GET: queries the current config hyperion uses.
* POST: applies config posted in body
  * Example with curl: ``curl -H "Content-Type: application/json" -XPOST -d "`cat simple-source.json`" localhost:8080/api/config``

/api/shutdown
-------------

Shuts down hyperion cleanly.

Config
======

The config is JSON and has the following syntax:
```
{
    "nodes": [< List of Node>]
    "connections": [<List of Connection>]
}
```

Connection
===========

A connection is what connects two nodes and is always directional. Some nodes
cannot have outgoing directions, because they are terminal ones (like TcpDestination)

Syntax:
```
{
    "from": <id of the source node>
    "to": <id of the destination node>
```

Node
====

The node is the central processing item of hyperion. Every node has common options and type-specific ones.

Syntax:
```
 {
   "id": <id of the node, GUID recommended>,
   "left": <integer, position on the frontend>,
   "top": <integer, position on the frontend>,
   "content": {
     "name": <name of the node, displayed on frontend>,
     "typeName": <type of the node>,
     "options": {
       <options block, specific to node type>
     }
   }
 }
```

Node types
==========

Tcp Source
----------

Tcp Source is responsible for receiving logs over a TCP connection. SSL is not yet supported.

TypeName: source

Options:
 * port: The port where the node is listening to connections
 * parser: Log parser type. Valid options are: raw, syslog, json

Tcp Destination
---------------

Tcp Destination is responsible for sending logs over a TCP connection. SSL is not yet supported.

TypeName: destination

Options:
 * host: hostname of the port
 * port: port number of the target
 * template: template format for outgoing log message

Parser
------

Standalone parser node for parsing fields.

TypeName: parser

Options:
 * field: Name of the message field to be parsed
 * parser: parser type: Valid options are: raw, syslog, json
 * prefix: The prefix of the resulting fields after parsing. Can be empty.

Set
---

Set the value of a message field

TypeName: set

Options:
 * fieldname: Name of the field which will be set
 * substvalue: Value to be set. Can be a template

Rewrite
-------

Substitue the value of a message field based on a regexp

TypeName: set

Options:
 * fieldname: name of the field which will be rewritten
 * matchexpr: regexp, matched part will be substituted in the field
 * substvalue: Value to be set. Can be a template

Filter
------

Filter messages based on regexp matching. If the value of the field is not matched, the message
is dropped (won't be propagated).

TypeName: filter

Options:
 * fieldname: name of the field the matching is based on
 * matchexpr: regexp, if not matched, message will be dropped

FileWriter
----------

Write messages to file.

TypeName: filewriter

Options:
 * filename: Name of the file to be written.
 * template: Template format for logs