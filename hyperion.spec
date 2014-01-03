Given a configboard with one node
When the user clicks commit
Then the node is created 

Given a configboard with one node
When the user moves the node
Then the node turns yellow

Given a configboard with one yellow node
When the user clicks commit
Then the node turns green

Given a configboard with one node and the backend has a node with the same id
When the user clicks commit
Then the node in the backend is updated with the frontend node properties

Backend:

Given a service with one node
    
