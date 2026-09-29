package com.ahmadre.hinata.user;

/**
 * Platform roles. They are independent of each other and of project and team
 * membership, and none of them opens anybody's projects.
 *
 * <ul>
 *   <li>{@link #ADMIN} runs the platform: accounts, server settings, the audit
 *       log. It reads no project, issue, article, team or time entry it was not
 *       given through a membership like anybody else.</li>
 *   <li>{@link #ORG_ADMIN} runs the organisation's legal side: working time,
 *       timesheet approvals, absences and their settings, holiday calendars and
 *       billing. It sees the personal data those duties need and no project
 *       content beyond them, and it has no access to the admin area.</li>
 *   <li>{@link #MEMBER} is everybody.</li>
 * </ul>
 */
public enum Role {
	ADMIN,
	ORG_ADMIN,
	MEMBER
}
