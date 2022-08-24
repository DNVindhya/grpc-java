## Build docker images:

In `.../grpc-java/examples` directory

1. `./gradlew installDist`


2. `./buildO11yImage.sh`   
   You should find the image in [gcr.io/vindhyan-gke-dev/o11y-examples]

---------------------------------------------------------------
## Prerequisites:

1. Kubernetes Cluster
2. Get authentication credentials for the cluster   
   `gcloud container clusters get-credentials <cluster-name>`

---------------------------------------------------------------
## Setup Kubernetes environment:
3. `./o11y-testing/example-setup.sh`


4. Enter the server pod  
   `kubectl get pods -n grpc-server-namespace-ui`  
   `kubectl exec -it grpc-server-deployment-ui-768cf9b4b-4zjgj -n grpc-server-namespace-ui  -- /bin/bash`


6. Get the local IP address  
   `cat /etc/hosts`  
```
    Sample output 
    10.24.1.7       grpc-server-deployment-ui-768cf9b4b-4zjgj
   ```

6. Start the example-server  
   `./build/install/examples/bin/hello-world-server`
```
Sample output 
   May 13, 2022 11:19:38 PM io.grpc.examples.helloworld.HelloWorldServer start
   INFO: Server started, listening on 50051
   ```

7. Enter the client pod   
   `kubectl get pods -n  grpc-client-namespace-ui`   
   `kubectl exec -it grpc-client-deployment-ui-7745bd5c96-j94nh -n  grpc-client-namespace-ui -- /bin/bash`


8. Run the client to send an RPC using the IP address of the server you got above:  
  `./build/install/examples/bin/hello-world-client world 10.24.1.7:50051`
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


11. If you want to run route-guide example for n number of times.  
for `n = 10`
`./build/install/examples/bin/hello-world-client buddy 10.24.1.7:50051 10`

12. To make server return error   
`./build/install/examples/bin/hello-world-client errorDATA_LOSS 10.24.1.7:50051`  
This will cause server to return DATA-LOSS error status.
---------------------------------------------------------------
## Bidi testing using [Route guide](https://github.com/grpc/grpc-java/tree/master/examples#basic-examples) example:
1. On the server pod, run  
`./build/install/examples/bin/route-guide-server`


2. On the client pod, run (assuming server pod IP is 10.88.2.15)  
`./build/install/examples/bin/route-guide-client 10.24.1.7:8980`


3. If you want to run route-guide example for n number of times.  
for `n = 10`  
`./build/install/examples/bin/route-guide-client 10.24.1.7:8980 10 `   


4. To make server return error in a bidi streaming call
`./build/install/examples/bin/route-guide-server 4 DATA_LOSS`
this will make the server return error DATA-LOSS after every 7th onNext() (globally counted).

---------------------------------------------------------------
## Clean up

`./o11y-testing/example-cleanup.sh`

