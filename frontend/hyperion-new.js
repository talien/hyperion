const nodeTypes = [{
  id: "source",
  name: "Source",
  options : { port : "0"},
},{
  id : "filter",
  name : "Filter",
  options : {
    fieldname : "",
    matchexpr : "" }
},{
  id : "rewrite",
  name : "Rewrite",
  options : {
    fieldname : "",
    matchexpr : "",
    substvalue : "" }
},{
  id : "counter",
  name : "Counter",
  options : {}
},{
  id : "tail",
  name : "Tail",
  options : {
    backlog : "0" }
},{
  id : "stats",
  name : "Statistics",
  options : {
    fieldname : ""}
},{
  id : "filewriter",
  name : "File Destination",
  options : {
    filename: "",
    template: ""}
}]

function clone(o) {
   var out, v, key;
   out = Array.isArray(o) ? [] : {};
   for (key in o) {
       v = o[key];
       out[key] = (typeof v === "object") ? copy(v) : v;
   }
   return out;
}

function connectNodes(from, to) {
  jsPlumb.connect({
    source : from,
    target : to,
    connector: ["Straight"],
    overlays: [
      [ "Arrow", { foldback:0.2 } ]
    ],
    endpoint: "Dot",
    anchor:"Continuous"
  });
}


function Context() {
  this.adding = false;
  this.selected = false;
  this.connecting = false;
  this.firstItem = false;
  this.secondItem = false;

  this.nodeProperties = {
        name : "",
        selectedType : "source",
        selectedOptions : null,
        selectedItem : null,
        types : nodeTypes }

}

Context.prototype.startAdd = function() {
   this.deselect()
   this.adding = true
}

Context.prototype.stopAdd = function(items, event) {
   if ((this.nodeProperties.name) && (this.nodeProperties.selectedType.id)) {
        this.adding = false;
        var optionsClone = null;
        if (this.nodeProperties.hasOptions) {
            optionsClone = clone(this.nodeProperties.selectedOptions)
        }
        items.addNew({
            left : event.originalEvent.layerX,
            top : event.originalEvent.layerY,
            content : {
                name : this.nodeProperties.name,
                typeName : this.nodeProperties.selectedType.id,
                options : optionsClone }
        })
        this.nodeProperties.selectedOptions = null
        this.nodeProperties.selectedType = "source"
        this.nodeProperties.selectedItem = null
  }
}

Context.prototype.setOptionsFor = function(typeName) {
  const that = this;
  this.nodeProperties.types.forEach(function(item) {
     if (item.id === typeName) {
        if (item.options) {
           that.nodeProperties.hasOptions = true;
           that.nodeProperties.selectedOptions = clone(item.options);
        } else {
           that.nodeProperties.hasOptions = false;
        }
     }
  });
}

Context.prototype.setOptions = function() {
     this.setOptionsFor(this.nodeProperties.selectedType.id)
}

Context.prototype.select = function(nodeId, graph, element) {
     this.selected = true;
     item = graph.getItemWithID(nodeId);
     if ((item.left != element.offsetLeft) || (item.top != element.offsetTop)) {
        item.moved = true
     }
     item.left = element.offsetLeft;
     item.top = element.offsetTop;
     this.nodeProperties.selectedItem = item;
}

Context.prototype.deselect = function() {
     this.selected = false;
}

Context.prototype.startConnect = function() {
     this.connecting = true;
}

Context.prototype.stopConnect = function() {
     this.connecting = false;
     this.firstItem = false;
     this.secondItem = false;
}

Context.prototype.onElementClicked = function(node, graph) {
    if (this.connecting) {
       if (this.firstItem === false) {
          this.firstItem = node.id;
       } else {
          this.secondItem = node.id;
          graph.connect(this.firstItem, this.secondItem);
          this.stopConnect();
       }
    } else {
       this.select(node.id, graph, node)
    }
}

function Graph() {
  this.items = []
  this.connections = []
}

Graph.prototype.add = function(item) {
  this.items.push(item);
  jsPlumb.draggable(item.id);
}

Graph.prototype.addNew = function(item) {
  item.id = 'a' + (this.items.length + 1);
  item.pending = true;
  this.add(item);
}

Graph.prototype.getItemWithID = function(id) {
  var result = null;
  this.items.forEach(function(item) {
    if (item.id === id) {
       result = item;
    }
  });
  return result;
}

Graph.prototype.getItemWithName = function(name) {
  var result = null;
  this.items.forEach(function(item) {
    if (item.content.name === name) {
       result = item;

    }
  });
  return result;
}

Graph.prototype.commit = function(errorhandler) {
  this.items.forEach(function(item) {
    if ((item.pending) || (item.moved)) {
        $.ajax({
           url : "/rest/create",
           data : JSON.stringify(item),
           contentType : 'application/json',
           type : 'POST',
           error : function(data) {
              errorhandler(data);
           },
           success : function(data) {
              item.pending = false;
              item.moved = false;
           }
        });
    }
  });

  this.connections.forEach(function(connection) {
    if (connection.pending) {
        $.ajax({
           url : "/rest/join/" + connection.from + "/" + connection.to,
           type : 'GET',
           error : function(data) {
              errorhandler(data);
           },
           success : function (data) {
              connection.pending = false;
           }
        });
    }
  });
}

Graph.prototype.isConnected = function(from, to) {
  var result = false;
  this.connections.forEach(function(connection) {
    if ((connection.from === from) && (connection.to === to)) {
      result = true;
    }
  });
  return result;
}

Graph.prototype.loadConnections = function(connections) {
  var that = this;
  connections.forEach(function(connection) {
     if (!that.isConnected(connection)) {
       console.log(connection);
       fromid = that.getItemWithName(connection.from).id
       toid = that.getItemWithName(connection.to).id
       that.connectWithPending(fromid, toid, false)
     }
  });
}

Graph.prototype.loadNode = function(node, scope) {
  if (!this.getItemWithID(node.id)) {
     this.add(node);
     this.dashboard.add(scope,node.content);
  }
}

Graph.prototype.loadNodes = function(scope, nodes) {
  var that = this;
  nodes.forEach(function(node) {
     that.loadNode(node, scope);
  });
}

Graph.prototype.load = function(scope) {
  var that = this;
  $.ajax({
       url : "/rest/config",
       type : 'GET',
       success : function(data) {
         that.loadNodes(scope, data.nodes);
         scope.$apply();
         that.loadConnections(data.connections);
       }
  });
}

Graph.prototype.connectWithPending = function(from, to, pending) {
  if (this.isConnected(from, to))
     return;
  connectNodes(from, to);
  fromname = this.getItemWithID(from).content.name;
  toname = this.getItemWithID(to).content.name;
  this.connections.push({
       from : fromname,
       to : toname,
       pending : pending
  });
}

Graph.prototype.connect = function(from, to) {
  this.connectWithPending(from, to, true);
}
   
Graph.prototype.shutdown = function() {
  $.ajax({
       url : "/rest/shutdown",
       type: 'GET'
  });
}

function Dashboard() {
  this.items = [];
}
    
Dashboard.prototype.getItemWithName = function(name) {
   var result = null;
   this.items.forEach(function(item) {
      if (item.name === name) {
        result = item;
      }
   });
   return result;
}

Dashboard.prototype.add = function(scope, node) {
   var update = null;
   var item = null;
   if (node.typeName === "counter") {
      item = {
        name : node.name,
        iscounter : true
      }
      update = function (item, data) {
        item.counter = data;
      }
   }

   if (node.typeName === "tail") {
      item = {
        name : node.name,
        istail : true
      }
      update = function (item, data) {
        item.messages = data;
      }
   }

   if (node.typeName === "stats") {
      item = {
        name : node.name,
        isstats : true
      }
      update = function(item, data) {
        item.stats = data;
      }
   }

   if (!item) {
     return;
   }

   this.items.push(item);

   repeater = function() {
     $.ajax({
        url : "/rest/" + node.typeName + "/" + node.name,
        type : 'GET',
        success : function(data) {
          update(item, data);
          scope.$apply();
        },
        complete : function() {
          setTimeout(repeater, 1000);
        }
     });
   }
   repeater()
}

const graph = new Graph();
const context = new Context();
const dashboard = new Dashboard();

const hyperionApp = angular.module("hyperionApp", [])
hyperionApp.controller("BoardController", function BoardController($scope) {
  $scope.graph = graph;
  $scope.context = context;
  $scope.dashboard = dashboard;
  $scope.graph.dashboard = dashboard;
  $scope.activeTab = 'landscape';

  $scope.isActiveTab = function (tab) {
    return ($scope.activeTab === tab)
  }

  $scope.setTab = function(tab) {
    $scope.activeTab = tab;
  }

  $scope.connectClicked = function() {
    $scope.context.connecting = true;
  }

  $scope.elementClicked = function($event) {
    $scope.context.onElementClicked($event.target, $scope.graph);
    $event.stopPropagation();
  }

  $scope.addClicked = function() {
    $scope.context.startAdd();
  }

  $scope.containerClicked = function($event) {
    if ($scope.context.adding) {
      $scope.context.stopAdd($scope.graph, $event)
    } else {
      if ($scope.context.selected) {
         $scope.context.deselect()
      }
    }
  }

  $scope.commitClicked = function() {
    var errorhandler = function (message) {
      $scope.context.error = message.responseText;
    }
    $scope.graph.commit(errorhandler);
  }

  $scope.loadClicked = function() {
    $scope.graph.load($scope);
  }

  $scope.shutdown = function() {
    $scope.graph.shutdown()
  }
});

hyperionApp.directive("hyperionNode", function () {
  function link(scope, element, attrs) {
    jsPlumb.draggable(element, { containment: "#landscape" })
  };
  return {
    replace: true,
    controller: "BoardController",
    link: link 
  };
});
