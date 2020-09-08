# Sonder

## Description
Sonder is a Java library providing TCP client-server asynchronous communication on high level abstraction.
It's implemented over NIO encapsulating hard work of selectors and some byte-level packet protocol.

## Requirements
```
Java 11 or higher.
```

## Maven Dependency

```xml
<dependency>
	<groupId>com.github.tix320</groupId>
	<artifactId>sonder</artifactId>
	<version>2.0.0</version>
</dependency>
```

## Usage
Bellow a small example to demonstrate client-server communication.
For the detailed information about the internal working and usage please see the [documentation](https://github.com/tix320/sonder/wiki).

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
import java.io.IOException;
import java.net.InetSocketAddress;

import com.github.tix320.kiwi.api.reactive.observable.MonoObservable;
import com.github.tix320.sonder.api.client.SonderClient;
import com.github.tix320.sonder.api.common.rpc.Endpoint;
import com.github.tix320.sonder.api.common.rpc.Origin;
import com.github.tix320.sonder.api.common.rpc.RPCProtocol;
import com.github.tix320.sonder.api.server.SonderServer;

@Endpoint("someService")
class ServerEndpoint {

	@Endpoint("someMethod")
	public Integer getLengthOfString(String s) {
		return s.length();
	}
}

class ServerTest {

	public static void main(String[] args) throws IOException {
		// Creating protocol for communication
		RPCProtocol rpcProtocol = SonderServer.getRPCProtocolBuilder()
				.registerEndpointClasses(ServerEndpoint.class)
				.build();

		// Creating the server itself and registering the protocol
		SonderServer sonderServer = SonderServer.forAddress(new InetSocketAddress(8888))
				.registerProtocol(rpcProtocol)
				.build();

		// start the server
		sonderServer.start();
	}
}

@Origin("someService")
interface ClientOrigin {

	@Origin("someMethod")
	MonoObservable<Integer> getLengthOfString(String s);
}

class ClientTest {
	public static void main(String[] args) throws IOException {
		// Creating protocol for communication
		RPCProtocol rpcProtocol = SonderClient.getRPCProtocolBuilder()
				.registerOriginInterfaces(ClientOrigin.class)
				.build();

		// Creating the сдшуте itself and registering the protocol
		SonderClient sonderClient = SonderClient.forAddress(new InetSocketAddress("localhost", 8888))
				.registerProtocol(rpcProtocol)
				.build();

		// connect to server
		sonderClient.connect();

		// Get RPC interface for some request
		ClientOrigin clientOrigin = rpcProtocol.getOrigin(ClientOrigin.class);

		// Call RPC method and subscribe to response
		clientOrigin.getLengthOfString("my_first_rpc_call").subscribe(length -> {
			System.out.println(length);
			assert length == 17;
		});
	}
}
```

## Credits
- Tigran Sargsyan - **[tix320](https://github.com/tix320).**

## License
This project is licensed under the Apache License - see the [LICENSE](https://github.com/tix320/sonder/blob/master/LICENSE) file for details.
