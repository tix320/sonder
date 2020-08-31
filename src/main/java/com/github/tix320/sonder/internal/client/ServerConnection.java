package com.github.tix320.sonder.internal.client;

import java.io.IOException;
import java.util.function.Consumer;

import com.github.tix320.sonder.internal.common.State;
import com.github.tix320.sonder.internal.common.communication.Pack;

public interface ServerConnection {

	void connect(Consumer<Pack> packConsumer) throws IOException;

	void send(Pack pack);

	boolean close() throws IOException;

	State getState();
}
