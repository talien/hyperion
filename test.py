import json, urllib2

parser = {
        'id' : 'parser',
        'left' : 50,
        'top' : 100,
        'content' : 
        {
          'name': 'parser',
          'typeName': 'source',
          'options': {
            'port':'1514'
          } 
        }
}

printer = {
        'id' : 'printer',
        'left' : 450,
        'top' : 200,
        'content' :
        {
            'name': 'printer',
            'typeName' : 'printer',
            'options': {}
        }
}

counter = {
        'id' : 'counter',
        'left' : 250,
        'top' : 200,
        'content' : {
            'name': 'counter',
            'typeName' : 'counter',
            'options': {}
        }
}

averagecounter = {
        'id' : 'averagecounter',
        'left' : 50,
        'top' : 200,
        'content' : {
            'name': 'average',
            'typeName' : 'averageCounter',
            'options': {
                'counter':'counter',
                'backlog':'20'
                }
        }
}

tail =  {
        'id' : 'tail',
        'left' : 50,
        'top' : 300,
        'content' : {
            'name': 'tail',
            'typeName' : 'tail',
            'options': {
                'backlog':'20'
            }
        }
}


rewrite = {
        'id' : 'rewrite',
        'left' : 250,
        'top' : 300,
        'content' : {
            'name' : 'rewrite',
            'typeName' : 'rewrite',
            'options': {
                'fieldname':'MESSAGE',
                'matchexpr':'PADD',
                'substvalue':'lofasz'
            }
        }
}

filternode = {
        'id' : 'filter',
        'left' : 450,
        'top' : 300,
        'content' : {
            'name' : 'filter',
            'typeName' : 'filter',
            'options' : {
                'fieldname' : 'MESSAGE',
                'matchexpr' : '.*10000.*'
            }
        }
}

def create(data):
    req = urllib2.Request('http://localhost:8080/rest/create')
    req.add_header('Content-Type', 'application/json')
    try:
        response = urllib2.urlopen(req, json.dumps(data))
    except urllib2.HTTPError as e:
        print("".join(e.readlines()))
        raise

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

