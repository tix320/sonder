package com.gitlab.tixtix320.sonder.internal.common.util;

public final class ByteArrayList {

	private byte[] data;

	private int index;

	public ByteArrayList(int initialSize) {
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
			System.arraycopy(data, 0, newData, 0, index);
			System.arraycopy(bytes, start, newData, index, length);
			data = newData;
		}
		index = index + length;
	}

	public byte[] asArray() {
		return data;
	}

	public void clear() {
		index = 0;
	}

	public int size() {
		return index;
	}

	public boolean isEmpty() {
		return index == 0;
	}
}
