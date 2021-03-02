package org.devocative.keights;

public class KeightsException extends RuntimeException {
	public KeightsException(String message, Object... args) {
		this(null, message, args);
	}

	public KeightsException(Throwable cause) {
		this(cause, null);
	}

	public KeightsException(Throwable cause, String message, Object... args) {
		super(getMessage(message, args), cause);
	}

	// ------------------------------

	private static String getMessage(String message, Object... args) {
		if (message != null) {
			if (args != null) {
				return String.format(message, args);
			}
			return message;
		}
		return null;
	}
}
