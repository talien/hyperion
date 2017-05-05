const hyperionApp = angular.module("hyperionApp", []);

const nodeTypes = [{
  id: "source",
  name: "Source",
  options: {port: "0"}
}, {
  id: "filter",
  name: "Filter",
  options: {
    fieldname: "",
    matchexpr: ""
  }
}, {
  id: "rewrite",
  name: "Rewrite",
  options: {
    fieldname: "",
    matchexpr: "",
    substvalue: ""
  }
}, {
  id: "counter",
  name: "Counter",
  options: {}
}, {
  id: "tail",
  name: "Tail",
  options: {
    backlog: "0"
  }
}, {
  id: "stats",
  name: "Statistics",
  options: {
    fieldname: ""
  }
}, {
  id: "filewriter",
  name: "File Destination",
  options: {
    filename: "",
    template: ""
  }
}];

function clone(o) {
  var out, v, key;
  out = Array.isArray(o) ? [] : {};
  for (key in o) {
    v = o[key];
    out[key] = (typeof v === "object") ? copy(v) : v;
  }
  return out;
}

hyperionApp.service('ContextService', function() {
  this.adding = false;
  this.selected = false;
  this.connecting = false;
  this.firstItem = false;
  this.secondItem = false;

  this.nodeProperties = {
    name: "",
    selectedType: "source",
    selectedOptions: null,
    selectedItem: null,
    types: nodeTypes
  }


  this.startAdd = function () {
    this.deselect();
    this.adding = true;
  };

  this.stopAdd = function (items, event) {
    if ((this.nodeProperties.name) && (this.nodeProperties.selectedType.id)) {
      this.adding = false;
      var optionsClone = null;
      if (this.nodeProperties.hasOptions) {
        optionsClone = clone(this.nodeProperties.selectedOptions);
      }
      items.addNew({
        left: event.originalEvent.layerX,
        top: event.originalEvent.layerY,
        content: {
          name: this.nodeProperties.name,
          typeName: this.nodeProperties.selectedType.id,
          options: optionsClone
        }
      });
      this.nodeProperties.selectedOptions = null;
      this.nodeProperties.selectedType = "source";
      this.nodeProperties.selectedItem = null;
    }
  };

  this.setOptionsFor = function (typeName) {
    const that = this;
    this.nodeProperties.types.forEach(function (item) {
      if (item.id === typeName) {
        if (item.options) {
          that.nodeProperties.hasOptions = true;
          that.nodeProperties.selectedOptions = clone(item.options);
        } else {
          that.nodeProperties.hasOptions = false;
        }
      }
    });
  };

  this.setOptions = function () {
    this.setOptionsFor(this.nodeProperties.selectedType.id)
  };

  this.select = function (nodeId, graph, element) {
    this.selected = true;
    item = graph.getItemWithID(nodeId);
    if ((item.left != element.offsetLeft) || (item.top != element.offsetTop)) {
      item.moved = true
    }
    item.left = element.offsetLeft;
    item.top = element.offsetTop;
    this.nodeProperties.selectedItem = item;
  }

  this.deselect = function () {
    this.selected = false;
  }

  this.startConnect = function () {
    this.connecting = true;
  }

  this.stopConnect = function () {
    this.connecting = false;
    this.firstItem = false;
    this.secondItem = false;
  }

  this.onElementClicked = function (node, graph) {
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
  };
});

hyperionApp.service('GraphService', function () {
  this.items = [];
  this.connections = [];

  this.getItems = function () {
    return this.items;
  };

  this.add = function (item) {
    this.items.push(item);
    jsPlumb.draggable(item.id);
  };

  this.addNew = function (item) {
    item.id = 'a' + (this.items.length + 1);
    item.pending = true;
    this.add(item);
  };

  this.getItemWithID = function (id) {
    var result = null;
    this.items.forEach(function (item) {
      if (item.id === id) {
        result = item;
      }
    });
    return result;
  };

  this.getItemWithName = function (name) {
    var result = null;
    this.items.forEach(function (item) {
      if (item.content.name === name) {
        result = item;
      }
    });
    return result;
  };

  this.commit = function (errorhandler) {
    this.items.forEach(function (item) {
      if ((item.pending) || (item.moved)) {
        $.ajax({
          url: "/rest/create",
          data: JSON.stringify(item),
          contentType: 'application/json',
          type: 'POST',
          error: function (data) {
            errorhandler(data);
          },
          success: function (data) {
            item.pending = false;
            item.moved = false;
          }
        });
      }
    });

    this.connections.forEach(function (connection) {
      if (connection.pending) {
        $.ajax({
          url: "/rest/join/" + connection.from + "/" + connection.to,
          type: 'GET',
          error: function (data) {
            errorhandler(data);
          },
          success: function (data) {
            connection.pending = false;
          }
        });
      }
    });
  };

  this.isConnected = function (from, to) {
    var result = false;
    this.connections.forEach(function (connection) {
      if ((connection.from === from) && (connection.to === to)) {
        result = true;
      }
    });
    return result;
  };

  this.loadConnections = function (connections) {
    var that = this;
    connections.forEach(function (connection) {
      if (!that.isConnected(connection)) {
        console.log(connection);
        fromid = that.getItemWithName(connection.from).id
        toid = that.getItemWithName(connection.to).id
        that.connectWithPending(fromid, toid, false)
      }
    });
  };

  this.loadNode = function (node, scope) {
    if (!this.getItemWithID(node.id)) {
      this.add(node);
      this.dashboard.add(scope, node.content);
    }
  };

  this.loadNodes = function (scope, nodes) {
    var that = this;
    nodes.forEach(function (node) {
      that.loadNode(node, scope);
    });
  };

  this.load = function (scope) {
    var that = this;
    $.ajax({
      url: "/rest/config",
      type: 'GET',
      success: function (data) {
        that.loadNodes(scope, data.nodes);
        scope.$apply();
        that.loadConnections(data.connections);
      }
    });
  };

  this.connectNodes = function (from, to) {
    jsPlumb.connect({
      source: from,
      target: to,
      connector: ["Straight"],
      overlays: [
        ["Arrow", {foldback: 0.2}]
      ],
      endpoint: "Dot",
      anchor: "Continuous"
    });
  };

  this.connectWithPending = function (from, to, pending) {
    if (this.isConnected(from, to))
      return;
    connectNodes(from, to);
    fromname = this.getItemWithID(from).content.name;
    toname = this.getItemWithID(to).content.name;
    this.connections.push({
      from: fromname,
      to: toname,
      pending: pending
    });
  };

  this.connect = function (from, to) {
    this.connectWithPending(from, to, true);
  };

  this.shutdown = function () {
    $.ajax({
      url: "/rest/shutdown",
      type: 'GET'
    });
  };
});

function Dashboard() {
  this.items = [];
}

Dashboard.prototype.getItemWithName = function (name) {
  var result = null;
  this.items.forEach(function (item) {
    if (item.name === name) {
      result = item;
    }
  });
  return result;
}

Dashboard.prototype.add = function (scope, node) {
  var update = null;
  var item = null;
  if (node.typeName === "counter") {
    item = {
      name: node.name,
      iscounter: true
    }
    update = function (item, data) {
      item.counter = data;
    }
  }

  if (node.typeName === "tail") {
    item = {
      name: node.name,
      istail: true
    }
    update = function (item, data) {
      item.messages = data;
    }
  }

  if (node.typeName === "stats") {
    item = {
      name: node.name,
      isstats: true
    }
    update = function (item, data) {
      item.stats = data;
    }
  }

  if (!item) {
    return;
  }

  this.items.push(item);

  repeater = function () {
    $.ajax({
      url: "/rest/" + node.typeName + "/" + node.name,
      type: 'GET',
      success: function (data) {
        update(item, data);
        scope.$apply();
      },
      complete: function () {
        setTimeout(repeater, 1000);
      }
    });
  }
  repeater()
}

const dashboard = new Dashboard();

hyperionApp.controller("BoardController", function BoardController($scope, GraphService, ContextService) {
  $scope.dashboard = dashboard;
  GraphService.dashboard = dashboard;
  $scope.items = GraphService.getItems();
  $scope.context = ContextService;

  $scope.connectClicked = function () {
    ContextService.connecting = true;
  }

  $scope.elementClicked = function ($event) {
    ContextService.onElementClicked($event.target, GraphService);
    $event.stopPropagation();
  }

  $scope.addClicked = function () {
    ContextService.startAdd();
  }

  $scope.containerClicked = function ($event) {
    if (ContextService.adding) {
      ContextService.stopAdd(GraphService, $event)
    } else {
      if (ContextService.selected) {
        ContextService.deselect()
      }
    }
  }

  $scope.commitClicked = function () {
    var errorhandler = function (message) {
      ContextService.error = message.responseText;
    }
    GraphService.commit(errorhandler);
  }

  $scope.loadClicked = function () {
    GraphService.load($scope);
  }

  $scope.shutdown = function () {
    GraphService.shutdown()
  }
});

hyperionApp.directive("hyperionNode", function () {
  function link(scope, element, attrs) {
    jsPlumb.draggable(element, {containment: "#landscape"})
  };
  return {
    replace: true,
    controller: "BoardController",
    link: link
  };
});