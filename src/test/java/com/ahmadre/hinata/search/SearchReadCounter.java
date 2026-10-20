package com.ahmadre.hinata.search;

import com.mongodb.event.CommandListener;
import org.bson.BsonDocument;
import com.mongodb.event.CommandStartedEvent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Counts the read commands one test thread sends to MongoDB, as {@code shifts.ShiftReadCounter}
 * does. The test runs the search's categories on the calling thread (an inline
 * {@link SearchExecutor}), so every read of one search lands here and nothing a scheduled job
 * reads in between does.
 */
final class SearchReadCounter {

	private static final Set<String> READS = Set.of("find", "aggregate", "count", "getMore", "distinct");

	static final List<String> commands = new CopyOnWriteArrayList<>();

	/** The read commands themselves, in the order they were sent, for a test that explains one. */
	static final List<BsonDocument> sent = new CopyOnWriteArrayList<>();

	private static volatile Thread counting;

	private SearchReadCounter() {
	}

	static CommandListener listener() {
		return new CommandListener() {
			@Override
			public void commandStarted(CommandStartedEvent event) {
				if (Thread.currentThread() == counting && READS.contains(event.getCommandName())) {
					commands.add(event.getCommandName() + " " + event.getCommand().get(event.getCommandName()));
					sent.add(BsonDocument.parse(event.getCommand().toJson()));
				}
			}
		};
	}

	static int reads(Runnable read) {
		commands.clear();
		sent.clear();
		counting = Thread.currentThread();
		try {
			read.run();
		}
		finally {
			counting = null;
		}
		return commands.size();
	}

	static void reset() {
		counting = null;
		commands.clear();
		sent.clear();
	}
}
