package com.github.tix320.sonder.internal.common;

import java.time.Duration;
import java.util.function.Function;

import com.github.tix320.sonder.api.common.communication.CertainReadableByteChannel;

/**
 * @author : Tigran Sargsyan
 * @since : 17.02.2021
 **/
public class ChannelUtils {

	public static void setChannelFinishedWarningHandler(CertainReadableByteChannel channel,
														Function<Duration, String> warningMessage) {
		long warningTimeoutSeconds = Math.max((long) Math.ceil(channel.getContentLength() * 5D / 1024 / 1024),
				1); // 5sec for 1MB
		Duration warningTimeout = Duration.ofSeconds(warningTimeoutSeconds);

		channel.completeness().map(none -> true).getOnTimout(warningTimeout, () -> false).subscribe(success -> {
			if (!success) {
				System.out.println(warningMessage.apply(warningTimeout));
				setChannelFinishedWarningHandler(channel, warningMessage);
			}
		});
	}
}
