package com.gitlab.tixtix320.sonder.api.common.communication;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

public interface Transfer {

	Headers getHeaders();

	ReadableByteChannel channel();

	long getContentLength();

	byte[] readAll() throws IOException;
}
