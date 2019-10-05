module sonder {
	requires kiwi;
	requires transitive com.fasterxml.jackson.core;
	requires transitive com.fasterxml.jackson.databind;
	requires transitive com.fasterxml.jackson.annotation;

	exports com.gitlab.tixtix320.sonder.api.client;
	exports com.gitlab.tixtix320.sonder.api.server;
	exports com.gitlab.tixtix320.sonder.api.common.communication;
	exports com.gitlab.tixtix320.sonder.api.common.rpc;
	exports com.gitlab.tixtix320.sonder.api.common.rpc.extra;
	exports com.gitlab.tixtix320.sonder.api.common.topic;

	opens com.gitlab.tixtix320.sonder.internal.common.communication to com.fasterxml.jackson.core, com.fasterxml.jackson.databind, com.fasterxml.jackson.annotation;
}
