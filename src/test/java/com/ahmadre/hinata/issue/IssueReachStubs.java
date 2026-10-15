package com.ahmadre.hinata.issue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link IssueReach} for unit tests whose subject is not access: everybody sees, nobody is a mere participant. */
final class IssueReachStubs {

	private IssueReachStubs() {
	}

	static IssueReach everyoneSees() {
		IssueReach reach = mock(IssueReach.class);
		when(reach.canSee(any(), any())).thenReturn(true);
		return reach;
	}
}
