package com.github.tix320.sonder.internal.common.event;

import com.github.tix320.sonder.api.common.event.EventListener;
import com.github.tix320.sonder.api.common.event.SonderEvent;

/**
 * @author Tigran Sargsyan on 22-Mar-20.
 */
public interface EventDispatcher extends EventListener {

	void fire(SonderEvent eventObject);
}
