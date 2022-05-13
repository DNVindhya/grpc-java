## Build docker images:

In `.../grpc-java/examples` directory

1. `./gradlew installDist`


2. `./buildO11yImage.sh`   
   You should find the image in [gcr.io/vindhyan-gke-dev/o11y-examples]

---------------------------------------------------------------
## Setup Kubernetes environment:
3. `./o11y-testing/example-setup.sh`


4. Enter the server pod  
   `kubectl get pods -n example-grpc-server`  
   `kubectl exec -it example-grpc-server-d9558f68c-hv5qm -n example-grpc-server  -- /bin/bash`


6. Get the local IP address  
   `cat /etc/hosts`  
```
    Sample output 
    10.84.1.10	example-grpc-server-d9558f68c-hv5qm
   ```

6. Start the example-server  
   `./build/install/examples/bin/hello-world-server`
```
Sample output 
   May 13, 2022 11:19:38 PM io.grpc.examples.helloworld.HelloWorldServer start
   INFO: Server started, listening on 50051
   ```

7. Enter the client pod   
   `kubectl get pods -n example-grpc-client`   
   `kubectl exec -it example-grpc-client-54cb8885fc-brk44 -n example-grpc-client -- /bin/bash`


8. Run the client to send an RPC using the IP address of the server you got above:  
  `./build/install/examples/bin/hello-world-client world 10.84.1.10:50051`
```
Sample output 
   May 13, 2022 11:19:46 PM io.grpc.examples.helloworld.HelloWorldClient greet
   INFO: Will try to greet world ...
   May 13, 2022 11:19:47 PM io.grpc.examples.helloworld.HelloWorldClient greet
   INFO: Greeting: Hello world
   ```


9. Or use the KubeDNS hostname as shown [here](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/#a-aaaa-records-1)  
`./build/install/examples/bin/hello-world-client world 10.84.1.10.example-grpc-server.pod.cluster.local:50051`
```
Sample output 
    May 13, 2022 11:19:46 PM io.grpc.examples.helloworld.HelloWorldClient greet
    INFO: Will try to greet world ...
    May 13, 2022 11:19:47 PM io.grpc.examples.helloworld.HelloWorldClient greet
    INFO: Greeting: Hello world
   ```

---------------------------------------------------------------
## Bidi testing using [Route guide](https://github.com/grpc/grpc-java/tree/master/examples#basic-examples) example:
1. On the server pod, run  
`./build/install/examples/bin/route-guide-server`


2. On the client pod, run (assuming server pod IP is 10.88.2.15)  
`./build/install/examples/bin/route-guide-client 10.88.2.15:8980`


3. If you want to run route-guide example for n number of times.  
for `n = 10`  
`./build/install/examples/bin/route-guide-client 10.88.2.15:8980 vindhyan-gke-dev 10 `

---------------------------------------------------------------
## Clean up

`./o11y-testing/example-cleanup.sh`

