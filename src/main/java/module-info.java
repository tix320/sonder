module sonder {
	requires kiwi;
	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;

	exports com.gitlab.tixtix320.sonder.api.common;
	exports com.gitlab.tixtix320.sonder.api.client;
	exports com.gitlab.tixtix320.sonder.api.server;
	exports com.gitlab.tixtix320.sonder.api.extra;
}
