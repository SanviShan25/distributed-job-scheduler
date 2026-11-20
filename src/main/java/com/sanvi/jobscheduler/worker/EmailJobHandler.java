package com.sanvi.jobscheduler.worker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class EmailJobHandler implements JobHandler {
	private static final long DEFAULT_DELAY_MS = 500;
	private static final Pattern DELAY_PATTERN = Pattern.compile("(?:^|\\s)delayMs=(\\d+)(?:\\s|$)");

	@Override
	public String type() {
		return "EMAIL";
	}

	@Override
	public void handle(String payload) throws Exception {
		Thread.sleep(delayFrom(payload));
		if (payload != null && payload.contains("fail")) {
			throw new RuntimeException("simulated failure");
		}
	}

	private long delayFrom(String payload) {
		if (payload == null) {
			return DEFAULT_DELAY_MS;
		}

		Matcher matcher = DELAY_PATTERN.matcher(payload);
		if (!matcher.find()) {
			return DEFAULT_DELAY_MS;
		}

		try {
			return Long.parseLong(matcher.group(1));
		} catch (NumberFormatException e) {
			return DEFAULT_DELAY_MS;
		}
	}
}
