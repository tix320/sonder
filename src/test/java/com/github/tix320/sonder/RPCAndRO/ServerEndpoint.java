package com.github.tix320.sonder.RPCAndRO;

import com.github.tix320.sonder.api.common.rpc.Endpoint;

@Endpoint("foo")
public class ServerEndpoint {

	@Endpoint("")
	public String getObject() {
		RemoteObjectImpl remoteObject = new RemoteObjectImpl();
		String objectId = "lmfao";
		RPCAndROTest.sonderServer.registerObject(remoteObject, objectId);
		return objectId;
	}
}
