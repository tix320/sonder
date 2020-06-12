# Sonder

Sonder is a Java library for building TCP client-server applications on high level abstraction.

## Requirements

```
Java 11 or higher.
```

## Dependency

```xml
<dependency>
	<groupId>com.github.tix320</groupId>
	<artifactId>sonder</artifactId>
	<version>0.24.0</version>
</dependency>
```

## Features

#### RPC

```
 * Transfer any objects (via json)
 * Transfer binary data
 * End to end transfering with channels
```

##### Topics (publish/subscribe)
```
 * Publish any objects (via json)
```

##### Implement your own protocol
```
Just make implementation of interface for receiving and sending data and register him.
```

## Usage

### RPC example


 Bellow was created server on port 8888 and specified packages of rpc endpoints.
 The @Endpoint annotations marks, that this class methods must be called from RPC service, and its value will make it unique.
 Identically for his method.
 
  Also was created client, which is connected to that server and specify packages of rpc interfaces.
  The @Origin annotations marks, that this interface must be used as RPC service, and its value will make it unique.
  Identically for his method.
  In most cases 'origin' methods returns MonoObservable, due this call is asynchronous, and yo can subscribe and process response, when will be available.
  
  ##### Server Endpoint
```java
package com.github.tix320.sonder.readme.server;

import com.github.tix320.sonder.api.common.rpc.Endpoint;

@Endpoint("myService")
public class MyEndpoint {

	@Endpoint("myMethod")
	public Integer getLengthOfString(String s) {
		return s.length();
	}
}
``` 
  
 ##### Server Main
```java
package com.github.tix320.sonder.readme.server;

import java.net.InetSocketAddress;

import com.github.tix320.sonder.api.server.SonderServerServer;

public class ServerTest {

	public static void main(String[] args) {
		SonderServer sonderServer = SonderServer.forAddress(new InetSocketAddress(8888))
                                    				.withRPCProtocol(builder -> builder.scanClasses(MyEndpoint.class))
                                    				.build();
	    sonderServer.start();
	}
}
```


 ##### Client RPC interface
```java
package com.github.tix320.sonder.readme.client;

import com.github.tix320.kiwi.api.observable.Observable;
import com.github.tix320.sonder.api.common.rpc.Origin;

@Origin("myService")
public interface MyService {

	@Origin("myMethod")
	MonoObservable<Integer> getLengthOfString(String s);
}
```

 ##### Client Main
```java
package com.github.tix320.sonder.readme.client;

import java.net.InetSocketAddress;

import com.github.tix320.sonder.api.client.Clonder;

public class ClientTest {
	public static void main(String[] args) {
		SonderClient sonderClient = SonderClient.forAddress(new InetSocketAddress("localhost", 8888))
                                    				.withRPCProtocol(builder -> builder.scanClasses(MyService.class))
                                    				.build();
        sonderClient.connect();

		MyService myService = sonderClient.getRPCService(MyService.class);
		myService.getLengthOfString("my first rpc call").subscribe(System.out::println);
	}
}

```

### Topic example


```java
package com.github.tix320.sonder.readme.server;

import java.net.InetSocketAddress;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tix320.sonder.api.client.Clonder;
import com.github.tix320.sonder.api.common.topic.Topic;
import com.github.tix320.sonder.api.server.Sonder;

public class ServerTest {

	public static void main(String[] args) {
		SonderServer sonderServer = SonderServer
        .forAddress(new InetSocketAddress(8888))
        .withTopicProtocol()
        .build();

        sonderServer.start();
	}
}

class Client1Test {
	public static void main(String[] args)
			throws InterruptedException {
		SonderClient sonderClient = SonderClient
            .forAddress(new InetSocketAddress("localhost", 8888))
            .withTopicProtocol()
            .build();

        sonderClient.connect();

		Topic<Integer> topic = sonderClient1.registerTopic("my-topic", new TypeReference<>() {});

		List.of(4, 5, 6).forEach(integer -> topic.publish(integer).subscribe(none -> 	System.out.println("Data was sent to subscribers")));

		topic.asObservable().subscribe(System.out::println);

	  /*
       Output is (order not guaranteed)
       1
       2
       3
       Data was sent to subscribers
        */
	}
}

class Client2Test {
	public static void main(String[] args) {
		SonderClient sonderClient = SonderClient
            .forAddress(new InetSocketAddress("localhost", 8888))
            .withTopicProtocol()
            .build();

        sonderClient.connect();

		Topic<Integer> topic = sonderClient1.registerTopic("my-topic", new TypeReference<>() {});

		List.of(1, 2, 3).forEach(integer -> topic.publish(integer).subscribe(none -> 	System.out.println("Data was sent to subscribers")));

		topic.asObservable().subscribe(System.out::println);

	  /*
       Output is (order not guaranteed)
       4
       5
       6
       Data was sent to subscribers
        */
	}
}
```

Here is a small example of how a library is easily configured and used.
Of course, you can also send requests from the server to the client,
or even from one client to another by specifying the client ID.
