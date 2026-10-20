package com.ahmadre.hinata.shifts;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

/**
 * Counts the read commands one test thread sends to MongoDB. The synchronous driver calls a command listener
 * on the thread that issues the command, so scheduled jobs that happen to run during a count stay out of it.
 * Counting every thread made the query-count tests flaky.
 */
final class ShiftReadCounter {

	private static final Set<String> READS = Set.of("find", "aggregate", "count", "getMore", "distinct");

	/** The read commands of the last count, as "name collection". */
	static final List<String> commands = new CopyOnWriteArrayList<>();

	private static volatile Thread counting;

	private ShiftReadCounter() {
	}

	/** A listener to register on the test's Mongo client. */
	static CommandListener listener() {
		return new CommandListener() {
			@Override
			public void commandStarted(CommandStartedEvent event) {
				if (Thread.currentThread() == counting && READS.contains(event.getCommandName())) {
					commands.add(event.getCommandName() + " " + event.getCommand().get(event.getCommandName()));
				}
			}
		};
	}

	/** Runs [read] on this thread and returns how many read commands it sent. */
	static int reads(Runnable read) {
		commands.clear();
		counting = Thread.currentThread();
		try {
			read.run();
		}
		finally {
			counting = null;
		}
		return commands.size();
	}

	/** Stops any count a failed test left running and forgets its commands. */
	static void reset() {
		counting = null;
		commands.clear();
	}

}
