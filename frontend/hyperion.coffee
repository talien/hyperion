

nodeTypes = [
  id : "source"
  name : "Source"
  options :
    port : "0"
,
  id : "filter"
  name : "Filter"
  options :
    fieldname : ""
    matchexpr : ""
,
  id : "rewrite"
  name : "Rewrite"
  options :
    fieldname : ""
    matchexpr : ""
    substvalue : ""
,
  id : "counter"
  name : "Counter"
  options : {}
,
  id : "tail"
  name : "Tail"
  options :
    backlog : "0"
,
  id : "stats"
  name : "Statistics"
  options :
    fieldname : ""
,
  id : "filewriter"
  name : "File Destination"
  options :
    filename: ""
]

clone = (obj) ->
   if not obj? or typeof obj isnt 'object'
      return obj
   
   newInstance = new obj.constructor()

   for key of obj
       newInstance[key] = clone obj[key]

   newInstance

connectNodes = (from, to) ->
  jsPlumb.connect
    source : from
    target : to
    connector: ["Straight"]
    overlays: [
      [ "Arrow", { foldback:0.2 } ]
    ]
    endpoint: "Dot"
    anchor:"Continuous"

class Context

  constructor : () ->
    @adding = false
    @selected = false
    @connecting = false
    @firstItem = false
    @secondItem = false

    @nodeProperties =
        name : ""
        selectedType : "source"
        selectedOptions : null
        selectedItem : null
        types : nodeTypes

  startAdd : () =>
     @deselect()
     @adding = true

  stopAdd : (items, event) =>
     if (@nodeProperties.name) and (@nodeProperties.selectedType.id)
        @adding = false
        items.add
            left : event.originalEvent.layerX
            top : event.originalEvent.layerY
            content :
                name : @nodeProperties.name
                typeName : @nodeProperties.selectedType.id
                options : clone @nodeProperties.selectedOptions if @nodeProperties.hasOptions
        @nodeProperties.selectedOptions = null
        @nodeProperties.selectedType = "source"
        @nodeProperties.selectedItem = null

  setOptionsFor : (typeName) =>
     for item in @nodeProperties.types
         if (item.id == typeName)
            if item.options?
              @nodeProperties.hasOptions = true
              @nodeProperties.selectedOptions = clone item.options
            else
              @nodeProperties.hasOptions = false

  setOptions : () =>
     @setOptionsFor @nodeProperties.selectedType.id

  select : (nodeId, graph, element) =>
     @selected = true
     item = graph.getItemWithID(nodeId)
     if (item.left != element.offsetLeft) or (item.top != element.offsetTop)
        item.moved = true
     item.left = element.offsetLeft
     item.top = element.offsetTop
     @nodeProperties.selectedItem = item

  deselect : () =>
     @selected = false

  startConnect : () =>
     @connecting = true

  stopConnect : () =>
     @connecting = false
     @firstItem = false
     @secondItem = false

  onElementClicked : (node, graph) =>
    if @connecting
       if @firstItem == false
          @firstItem = node.id
       else
          @secondItem = node.id
          graph.connect @firstItem, @secondItem
          @stopConnect()
    else
       @select node.id, graph, node

class Graph
  items : []
  connections : []

  add : (item) =>
    item.id = 'a' + (@items.length + 1)
    item.pending = true
    @items.push item
    jsPlumb.draggable item.id

  getItemWithID : (id) =>
    for item in @items
      if item.id == id
         return item

  getItemWithName : (name) =>
    for item in @items
      if item.content.name == name
         return item

  commit : (errorhandler) =>
    for item in @items
      if item.pending or item.moved
        $.ajax
           url : "/rest/create"
           data : JSON.stringify item
           contentType : 'application/json'
           type : 'POST'
           error : (data) =>
              errorhandler data
           success : (data) =>
              item.pending = false
              item.moved = false

    for connection in @connections
      if connection.pending
        $.ajax
           url : "/rest/join/" + connection.from + "/" + connection.to
           type : 'GET'
           error : (data) =>
              errorhandler data
           success : (data) =>
              connection.pending = false

   loadConnections : (connections) =>
     for connection in connections
        fromid = (@getItemWithName connection.from).id
        toid = (@getItemWithName connection.to).id
        @connectWithPending fromid, toid, false

   activateNode : (node, scope) =>
     @dashboard.add scope,node.content

   activateNodes : (scope) =>
     for node in @items
       @activateNode node, scope

   load : (scope) =>
     $.ajax
       url : "/rest/config"
       type : 'GET'
       success : (data) =>
         @items = data.nodes
         @activateNodes(scope)
         scope.$apply()
         @loadConnections data.connections

   isConnected : (from, to) =>
     for connection in @connections
         if connection.from == from and connection.to == to
            return true
     return false

   connectWithPending : (from, to, pending) =>
     if (@isConnected from, to)
        return
     connectNodes from, to
     fromname = (@getItemWithID from).content.name
     toname = (@getItemWithID to).content.name
     @connections.push
       from : fromname
       to : toname
       pending : pending

   connect : (from, to) =>
     @connectWithPending from, to, true

class Dashboard
  items : []

  getItemWithName : (name) =>
    for item in @items
      if item.name == name
         return item

  add : (scope, node) =>
    if node.typeName == "counter"
      item =
        name : node.name
        iscounter : true
      update = (item, data) ->
        item.counter = data

    if node.typeName == "tail"
      item =
        name : node.name
        istail : true
      update = (item, data) ->
        item.messages = data

    if node.typeName == "stats"
      item =
        name : node.name
        isstats : true
      update = (item, data) ->
        item.stats = data

    return if !item?

    @items.push item

    repeater = () =>
      $.ajax
        url : "/rest/" + node.typeName + "/" + node.name
        type : 'GET'
        success : (data) ->
          update(item, data)
          scope.$apply()
        complete : () ->
          setTimeout(repeater, 1000)
    repeater()


graph = new Graph
context = new Context
dashboard = new Dashboard

hyperionApp = angular.module "hyperionApp", []
hyperionApp.controller "BoardController", ($scope) ->
  $scope.graph = graph
  $scope.context = context
  $scope.dashboard = dashboard
  $scope.graph.dashboard = dashboard

  $scope.connectClicked = () ->
    $scope.context.connecting = true

  $scope.elementClicked = ($event) ->
    $scope.context.onElementClicked $event.target, $scope.graph
    $event.stopPropagation()

  $scope.addClicked = () ->
    $scope.context.startAdd()

  $scope.containerClicked = ($event) ->
    if ($scope.context.adding)
      $scope.context.stopAdd($scope.graph, $event)
    else
      if $scope.context.selected
         $scope.context.deselect()

  $scope.commitClicked = () ->
    errorhandler = (message) ->
      $scope.context.error = message.responseText
    $scope.graph.commit errorhandler

  $scope.loadClicked = () ->
    $scope.graph.load $scope


hyperionApp.directive "hyperionNode", ->
  replace: true
  controller: "BoardController"
  link: (scope, element, attrs) ->

    jsPlumb.draggable element,
      containment: "#landscape"

#jsPlumb.ready ->

  #graph.add
  #    left : 200
  #    top : 300
  #    content :
  #      name : "almafa"
  #      typeName : "source"
  #      options :
  #          port : "1514"
