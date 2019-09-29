module sonder {
	requires kiwi;
	requires transitive com.fasterxml.jackson.core;
	requires transitive com.fasterxml.jackson.databind;
	requires transitive com.fasterxml.jackson.annotation;

	exports com.gitlab.tixtix320.sonder.api.common;
	exports com.gitlab.tixtix320.sonder.api.common.extra;
	exports com.gitlab.tixtix320.sonder.api.client;
	exports com.gitlab.tixtix320.sonder.api.server;
}
