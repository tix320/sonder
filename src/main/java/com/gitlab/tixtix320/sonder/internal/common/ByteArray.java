package com.gitlab.tixtix320.sonder.internal.common;

public final class ByteArray {

	private byte[] data;

	private int index;

	public ByteArray() {
		this.data = new byte[512];
		this.index = 0;
	}

	public void addBytes(byte[] bytes, int start, int length) {
		if (length < data.length - index) {
			System.arraycopy(bytes, start, data, index, length);
		}
		else {
			int newDataSize = Math.max(data.length, length) * 2;
			byte[] newData = new byte[newDataSize];
			System.arraycopy(data, 0, newData, 0, data.length);
			System.arraycopy(bytes, start, newData, data.length, length);
			data = newData;
		}
		index = index + length;
	}

	public byte[] getBytes() {
		return data;
	}

	public void reset() {
		index = 0;
	}

	public int size() {
		return index;
	}

	public boolean isEmpty() {
		return index == 0;
	}
}
