/*
 * Copyright (C) 2010-2014 Geometer Plus <contact@geometerplus.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader.synchroniser;

import java.io.*;
import java.util.*;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.simple.JSONValue;

import org.geometerplus.zlibrary.core.network.*;
import org.geometerplus.fbreader.book.*;
import org.geometerplus.android.fbreader.libraryService.BookCollectionShadow;

public class SynchroniserService extends Service implements IBookCollection.Listener, Runnable {
	private final BookCollectionShadow myCollection = new BookCollectionShadow();
	private volatile Thread mySynchronizationThread;

	private final List<Book> myQueue = Collections.synchronizedList(new LinkedList<Book>());
	private final Set<Book> myProcessed = new HashSet<Book>();

	@Override
	public IBinder onBind(Intent intent) {
		myCollection.bindToService(this, this);
		return null;
	}

	private void addBook(Book book) {
		if (!myProcessed.contains(book) && book.File.getPhysicalFile() != null) {
			myQueue.add(book);
		}
	}

	@Override
	public synchronized void run() {
		System.err.println("SYNCHRONIZER BINDED TO LIBRARY");
		myCollection.addListener(this);
		if (mySynchronizationThread == null) {
			mySynchronizationThread = new Thread() {
				public void run() {
					System.err.println("HELLO THREAD");
					for (BookQuery q = new BookQuery(new Filter.Empty(), 20);; q = q.next()) {
						final List<Book> books = myCollection.books(q);
						if (books.isEmpty()) {
							break;
						}
						for (Book b : books) {
							addBook(b);
						}
					}
					while (!myQueue.isEmpty()) {
						final Book book = myQueue.remove(0);
						if (myProcessed.contains(book)) {
							continue;
						}
						myProcessed.add(book);
						System.err.println("Processing " + book.getTitle() + " [" + book.File.getPath() + "]");
						uploadBookToServer(book);
					}
					System.err.println("BYE-BYE THREAD");
					mySynchronizationThread = null;
				}
			};
			mySynchronizationThread.setPriority(Thread.MIN_PRIORITY);
			mySynchronizationThread.start();
		}
	}

	private static String toJSON(Object object) {
		final StringWriter writer = new StringWriter();
		try {
			JSONValue.writeJSONString(object, writer);
		} catch (IOException e) {
			throw new RuntimeException("JSON serialization failed", e);
		}
		return writer.toString();
	}

	private static abstract class Request extends ZLNetworkRequest {
		private final static String BASE_URL = "https://books.fbreader.org/app/";

		Request(String app, Object data) {
			super(BASE_URL + app, toJSON(data), true);
		}

		@Override
		public void handleStream(InputStream stream, int length) throws IOException, ZLNetworkException {
			final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
			final StringBuilder buffer = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				buffer.append(line);
			}
			processResponse(JSONValue.parse(buffer.toString()));
		}

		final void perform() {
			try {
				ZLNetworkManager.Instance().perform(this);
			} catch (ZLNetworkException e) {
				e.printStackTrace();
			}
		}

		protected abstract void processResponse(Object response);
	}

	private void uploadBookToServer(Book book) {
		final UID uid = BookUtil.createUid(book.File.getPhysicalFile(), "SHA-1");
		if (uid == null) {
			System.err.println("Failed: SHA-1 checksum not computed");
			return;
		}
		System.err.println("SHA-1: " + uid.Id);
		new Request("books.by.hash", Collections.singletonMap("sha1", uid.Id)) {
			public void processResponse(Object response) {
				System.err.println("RESPONSE = " + response);
			}
		}.perform();
	}

	@Override
	public void onDestroy() {
		myCollection.removeListener(this);
		myCollection.unbind();
		System.err.println("SYNCHRONIZER UNBINDED FROM LIBRARY");
		super.onDestroy();
	}

	@Override
	public void onBookEvent(BookEvent event, Book book) {
		switch (event) {
			default:
				break;
			case Added:
				addBook(book);
				break;
		}
	}

	@Override
	public void onBuildEvent(IBookCollection.Status status) {
	}
}