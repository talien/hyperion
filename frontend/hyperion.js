const hyperionApp = angular.module("hyperionApp", ["angular-uuid", "ui.bootstrap", "ngSanitize"]);

const nodeTypes = [{
  id: "source",
  name: "Tcp Source",
  options: {
    port: "0",
    parser: [ "raw", "syslog", "json"]
  }
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
}, {
  id: "parser",
  name: "Parser Node",
  options: {
    prefix: "",
    field: "",
    parser: ["syslog", "json", "raw"]
  }
}, {
  id: "destination",
  name: "Tcp Destination",
  options: {
    host: "",
    port: "",
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

hyperionApp.service('ContextService', function(GraphService, $uibModal) {
  this.selected = false;
  this.connecting = false;
  this.firstItem = false;

  this.nodeProperties = {
    name: "",
    selectedType: "source",
    selectedOptions: null,
    selectedItem: null,
    types: nodeTypes
  };

  this.add = function (event) {
    var modalInstance = $uibModal.open({
      ariaLabelledBy: 'modal-title',
      ariaDescribedBy: 'modal-body',
      templateUrl: 'addnodedialog.html',
      controller: 'AddDialogCtrl',
      controllerAs: '$ctrl',
      resolve: {
        event: function () {
          return event;
        }
      }
    });
   
  };


  this.select = function (element) {
    this.selected = true;
    item = GraphService.getItemWithID(element.id);
    item.left = element.offsetLeft;
    item.top = element.offsetTop;
    this.nodeProperties.selectedItem = item;
  }

  this.deselect = function () {
    this.stopConnect();
    this.selected = false;
  }

  this.startConnect = function (connectType) {
    this.connecting = connectType;
  }

  this.stopConnect = function () {
    this.connecting = false;
    this.firstItem = false;
  }

  this.onElementClicked = function (element) {
    if (this.connecting) {
      if (this.firstItem === false) {
        this.firstItem = element.id;
      } else {
        if (this.connecting === "connect") {
          GraphService.connect(this.firstItem, element.id);
          this.stopConnect();
        } else {
          GraphService.disconnect(this.firstItem, element.id);
          this.stopConnect();
        }
      }
    } else {
      this.select(element);
    }
  };
  
  this.onContainerClicked = function($event) {
    this.deselect();
    this.add($event);
  }
});

hyperionApp.service('HyperionBackend', function($http) {
  this.getConfig = function() {
    return $http.get("/rest/config");
  }

  this.shutDown = function() {
    return $http.post("/rest/shutdown");
  }

  this.postConfig = function(data) {
    return $http.post("/rest/config", JSON.stringify(data));
  }  

});

hyperionApp.service('GraphService', function (uuid, HyperionBackend) {
  this.items = [];
  this.connections = [];
  this.plumberConnections = [];
  this.differ = jsondiffpatch.create({
      arrays: {
        detectMove: true,
        includeValueOnMove: false
      },
      propertyFilter: function(name, context) {
        return ((name.slice(0, 1) !== '$') && (name.slice(0, 1) !== '_'));
      },
      objectHash: function(obj) {
        return obj._id || obj.id;
      }
  });

  this.getItems = function () {
    return this.items;
  };

  this.add = function (item) {
    this.items.push(item);
    jsPlumb.draggable(item.id);
  };

  this.addNew = function (item) {
    item.id = uuid.v4();
    this.add(item);
  };

  this.removeItemByID = function(id) {
    this.items = this.items.filter((item ) => (item.id !== id));
    this.connections = this.connections.filter((item) => ((item.from !== id) && (item.to !== id)));
  }

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

  this.commit = function(errorhandler) {
    config = {
      nodes : this.items,
      connections : this.connections
    }
    HyperionBackend.postConfig(config).catch(errorhandler);
  }

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
        that.connectWithPending(connection.from, connection.to, false)
      }
    });
  };

  this.loadNode = function (node, scope) {
    if (!this.getItemWithID(node.id)) {
      this.add(node);
      this.dashboard.add(scope, node);
    }
  };

  this.loadNodes = function (scope, nodes) {
    var that = this;
    nodes.forEach(function (node) {
      that.loadNode(node, scope);
    });
  };

  this.load = function (scope) {
    HyperionBackend.getConfig().then((response) => {
        const data = response.data;
        this.loadNodes(scope, data.nodes);
        this.loadConnections(data.connections);
    });
  };

  this.connectNodes = function (from, to) {
    const connection = jsPlumb.connect({
      source: from,
      target: to,
      connector: ["Straight"],
      overlays: [
        ["Arrow", {foldback: 0.2}]
      ],
      endpoint: "Dot",
      anchor: "Continuous"
    });
    this.plumberConnections.push({
      from: from,
      to: to,
      connection: connection
    });
  };

  this.connectWithPending = function (from, to) {
    if (this.isConnected(from, to))
      return;
    this.connectNodes(from, to);
    this.connections.push({
      from: from,
      to: to
    });
  };

  this.connect = function (from, to) {
    this.connectWithPending(from, to, true);
  };

  this.diff = function () {
    return HyperionBackend.getConfig().then((response) => {
        const serverConfig = response.data;
        const config = {
          nodes : this.items,
          connections : this.connections
        }
        var delta = this.differ.diff(serverConfig, config);
        return { diff:delta, config:serverConfig };
    });
  }

  this.disconnect = function(from, to) {
    this.connections = this.connections.filter((item) => ((item.from !== from) || (item.to !== to)));
    var removeableConnection;
    var newPlumberConnections = []; 
    this.plumberConnections.forEach((item) => {
      if ((item.from === from) && (item.to === to)) {
        removeableConnection = item;
      } else {
        newPlumberConnections.push(item);
      }
    });
    this.plumberConnections = newPlumberConnections;
    jsPlumb.detach(removeableConnection.connection);
  };

});

function Dashboard() {
  this.items = [];
}

Dashboard.prototype.add = function (scope, node) {
  var update = null;
  var item = null;
  if (node.content.typeName === "counter") {
    item = {
      id: node.id,
      name: node.content.name,
      iscounter: true
    }
    update = function (item, data) {
      item.counter = data;
    }
  }

  if (node.content.typeName === "tail") {
    item = {
      id: node.id,
      name: node.content.name,
      istail: true
    }
    update = function (item, data) {
      item.messages = data;
    }
  }

  if (node.content.typeName === "stats") {
    item = {
      id: node.id,
      name: node.content.name,
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
      url: "/rest/" + node.content.typeName + "/" + node.id,
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

hyperionApp.controller("DiffDialogCtrl", function DiffDialogCtrl($scope, GraphService, $sanitize) {
   $scope.content = "";
   GraphService.diff().then((data) => {
     $scope.content = jsondiffpatch.formatters.html.format(data.diff, data.config);
   });

});

hyperionApp.controller("AddDialogCtrl", function AddDialogCtrl($scope, $uibModalInstance, GraphService, event) {
  
  $scope.nodeProperties = {
    name: "",
    selectedType: "source",
    selectedOptionsView: null,
    selectedOptionsModel: null,
    selectedItem: null
  };

  $scope.types = nodeTypes;


  $scope.cancel = function() {
    $uibModalInstance.close();
  };

  $scope.cloneOptions = function (options) {
    result = {};
    for (var key in options) {
      result[key] = "";
    }
    return result;
  };

  $scope.isOption = function(optionName) {
    return Array.isArray($scope.nodeProperties.selectedOptionsView[optionName]);
  }
  
  $scope.setOptionsFor = function (typeName) {
    $scope.types.forEach(function (type) {
      if (type.id === typeName) {
        if (type.options) {
          $scope.nodeProperties.hasOptions = true;
          $scope.nodeProperties.selectedOptionsModel = $scope.cloneOptions(type.options);
          $scope.nodeProperties.selectedOptionsView = type.options;
        } else {
          $scope.nodeProperties.hasOptions = false;
        }
      }
    });
  };

  $scope.setOptions = function () {
    $scope.setOptionsFor($scope.nodeProperties.selectedType.id)
  };

  
  $scope.add = function() {
     if (($scope.nodeProperties.name) && ($scope.nodeProperties.selectedType.id)) {
      var optionsClone = null;
      if ($scope.nodeProperties.hasOptions) {
        optionsClone = clone($scope.nodeProperties.selectedOptionsModel);
      }
      GraphService.addNew({
        left: event.originalEvent.layerX,
        top: event.originalEvent.layerY,
        content: {
          name: this.nodeProperties.name,
          typeName: this.nodeProperties.selectedType.id,
          options: optionsClone
        }
      });
      $scope.nodeProperties.selectedOptions = null;
      $scope.nodeProperties.selectedType = "source";
      $scope.nodeProperties.selectedItem = null;
    }
    $uibModalInstance.close();
  }

});


hyperionApp.controller("BoardController", function BoardController($scope, GraphService, ContextService, HyperionBackend, $uibModal) {
  $scope.dashboard = dashboard;
  GraphService.dashboard = dashboard;
  $scope.items = GraphService.getItems();
  $scope.context = ContextService;

  $scope.connectClicked = function () {
    ContextService.startConnect("connect");
  }

  $scope.elementClicked = function ($event) {
    ContextService.onElementClicked($event.currentTarget);
    $event.stopPropagation();
  }

  $scope.containerClicked = function ($event) {
    ContextService.onContainerClicked($event);
  };

  $scope.commitClicked = function () {
    var errorhandler = function (message) {
      ContextService.error = message.responseText;
    }
    GraphService.commit(errorhandler);
  }

  $scope.loadClicked = function () {
    GraphService.load($scope);
  }

  $scope.diffClicked = function() {
    var modalInstance = $uibModal.open({
      ariaLabelledBy: 'modal-title',
      ariaDescribedBy: 'modal-body',
      templateUrl: 'diffdialog.html',
      controller: 'DiffDialogCtrl',
      controllerAs: '$ctrl',
      resolve: {
        items: function () {
          return null;
        }
      }
    });
  }

  $scope.shutdown = function () {
    HyperionBackend.shutDown();
  }

  GraphService.load($scope);
});

hyperionApp.directive("hyperionNode", function (GraphService, ContextService) {
  function link(scope, element, attrs) {
    jsPlumb.draggable(element, {containment: "#landscape"})

    scope.remove = function($event) {
      GraphService.removeItemByID(scope.item.id);
      $event.stopPropagation();
      jsPlumb.remove(element[0].id);
    }

    scope.connect = function($event) {
      ContextService.startConnect("connect");
      ContextService.onElementClicked(element[0], GraphService);
      $event.stopPropagation();
    }

    scope.disconnect = function($event) {
      ContextService.startConnect("disconnect");
      ContextService.onElementClicked(element[0], GraphService);
      $event.stopPropagation();
    }

  };
  return {
    replace: true,
    controller: "BoardController",
    link: link
  };
});
