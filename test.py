import json, urllib2

parser = {
        'name': 'parser',
        'typeName': 'source',
        'options': {}
}

printer = {
        'name': 'printer',
        'typeName' : 'printer',
        'options': {}
}

counter = {
        'name': 'counter',
        'typeName' : 'counter',
        'options': {}
}

averagecounter = {
        'name': 'average',
        'typeName' : 'averageCounter',
        'options': {
            'counter':'counter',
            'backlog':'20'
            }
}

tail =  {
        'name': 'tail',
        'typeName' : 'tail',
        'options': {
            'backlog':'20'
        }
}


rewrite = {
        'name' : 'rewrite',
        'typeName' : 'rewrite',
        'options': {
            'fieldname':'MESSAGE',
            'matchexpr':'PADD',
            'substvalue':'lofasz'
        }
}

filternode = {
        'name' : 'filter',
        'typeName' : 'filter',
        'options' : {
            'fieldname' : 'MESSAGE',
            'matchexpr' : '.*10000.*'
        }
}

def create(data):
    req = urllib2.Request('http://localhost:8080/rest/create')
    req.add_header('Content-Type', 'application/json')
    response = urllib2.urlopen(req, json.dumps(data))

def connect(fromNode, toNode):
    response = urllib2.urlopen(urllib2.Request('http://localhost:8080/rest/join/%s/%s' % (fromNode, toNode), ""))

create(parser)
create(printer)
create(counter)
create(averagecounter)
create(rewrite)
create(filternode)
create(tail)

connect("parser", "rewrite")
connect("rewrite", "filter")
connect("filter", "printer")
connect("rewrite", "counter")
connect("rewrite","tail")

