module com.github.tix.sonder {
	requires transitive com.github.tix.kiwi;
	requires transitive com.github.tix.skimp;

	requires transitive com.fasterxml.jackson.core;
	requires transitive com.fasterxml.jackson.databind;
	requires transitive com.fasterxml.jackson.annotation;

	exports com.github.tix320.sonder.api.common;
	exports com.github.tix320.sonder.api.common.communication;
	exports com.github.tix320.sonder.api.common.communication.channel;
	exports com.github.tix320.sonder.api.common.rpc;
	exports com.github.tix320.sonder.api.common.rpc.extra;
	exports com.github.tix320.sonder.api.client;
	exports com.github.tix320.sonder.api.client.event;
	exports com.github.tix320.sonder.api.client.rpc;
	exports com.github.tix320.sonder.api.server;
	exports com.github.tix320.sonder.api.server.event;
	exports com.github.tix320.sonder.api.server.rpc;
}
