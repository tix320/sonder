# Sonder

Sonder is a Java library for building TCP client-server applications on high level abstraction.

## Requirements

```
Java 11 or higher.
```

## Dependency

```xml
<dependency>
	<groupId>com.gitlab.tixtix320</groupId>
	<artifactId>sonder</artifactId>
	<version>0.11.1</version>
</dependency>
```

## Features

#### RPC
```
 * Transfer any object (via json)
 * Transfer binary data
 * Transfer via channel end to end without processing
```

##### Topics (publish/subscribe)
```
 * Transfer any object (via json)
```

## Usage

### RPC example


 Bellow was created serve on port 8888 and specify packages of rpc endpoints.
 The @Endpoint annotations marks, that this interface must be called from RPC service, and its value will make it unique.
 Identically for his method.
 
  Also was created client, which is connected to that server and specify packages of rpc interfaces.
  The @Origin annotations marks, that this interface must be used as RPC service, and its value will make it unique.
  Identically for his method.
  In most cases 'origin' methods returns Observable, due this call is asynchronous, and yo can subscribe and process response, when will be available.
  
 ##### Server Main
```java
package com.gitlab.tixtix320.sonder.readme.server;

import java.net.InetSocketAddress;

import com.gitlab.tixtix320.sonder.api.server.Sonder;

public class ServerTest {

	public static void main(String[] args) {
		Sonder sonder = Sonder.forAddress(new InetSocketAddress(8888))
				.withRPCProtocol("com.gitlab.tixtix320.sonder.readme.server")
				.build();
	}
}
```

 ##### Server Endpoint
```java
package com.gitlab.tixtix320.sonder.readme.server;

import com.gitlab.tixtix320.sonder.api.common.rpc.Endpoint;

@Endpoint("myService")
public class MyEndpoint {

	@Endpoint("myMethod")
	public Integer getLengthOfString(String s) {
		return s.length();
	}
}


```

 ##### Client Main
```java
package com.gitlab.tixtix320.sonder.readme.client;

import java.net.InetSocketAddress;

import com.gitlab.tixtix320.sonder.api.client.Clonder;

public class ClientTest {
	public static void main(String[] args) {
		Clonder clonder = Clonder.forAddress(new InetSocketAddress("localhost", 8888))
				.withRPCProtocol("com.gitlab.tixtix320.sonder.readme.client")
				.build();
		MyService myService = clonder.getRPCService(MyService.class);
		myService.getLengthOfString("my first rpc call").subscribe(System.out::println);
	}
}

```

 ##### Client RPC interface
```java
package com.gitlab.tixtix320.sonder.readme.client;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.api.common.rpc.Origin;

@Origin("myService")
public interface MyService {

	@Origin("myMethod")
	Observable<Integer> getLengthOfString(String s);
}
```




### Topic example


```java
package com.gitlab.tixtix320.sonder.readme.server;

import java.net.InetSocketAddress;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gitlab.tixtix320.sonder.api.client.Clonder;
import com.gitlab.tixtix320.sonder.api.common.topic.Topic;
import com.gitlab.tixtix320.sonder.api.server.Sonder;

public class ServerTest {

	public static void main(String[] args) {
		Sonder sonder = Sonder.forAddress(new InetSocketAddress(8888)).withTopicProtocol().build();
	}
}

class Client1Test {
	public static void main(String[] args)
			throws InterruptedException {
		Clonder clonder = Clonder.forAddress(new InetSocketAddress("localhost", 8888)).withTopicProtocol().build();
		Topic<List<String>> topic = clonder.registerTopic("my-topic", new TypeReference<>() {});
		topic.asObservable().subscribe(System.out::println);
		topic.publish(List.of("a", "b", "c")).subscribe(none -> {
			System.out.println("Data was sended to subscribers");
		});

       /*
       Output is (order not guaranteed)
       [e, d, f]
       Data was sended to subscribers
        */
	}
}

class Client2Test {
	public static void main(String[] args) {
		Clonder clonder = Clonder.forAddress(new InetSocketAddress("localhost", 8888)).withTopicProtocol().build();
		Topic<List<String>> topic = clonder.registerTopic("my-topic", new TypeReference<>() {});
		topic.asObservable().subscribe(System.out::println);
		topic.publish(List.of("e", "d", "f")).subscribe(none -> {
			System.out.println("Data was sended to subscribers");
		});

		 /*
       Output is (order not guaranteed)
       [a, b, c]
       Data was sended to subscribers
        */
	}
}
```

# Summary
Here is a very small example of how a library is easily configured and used.
Of course, you can also send requests from the server to the client,
or even from one client to another by specifying the client ID.
Also you can specify your own protocols and register theme (RPC and Topics are built-in).
For any question, please contact me and thank you for your support.
