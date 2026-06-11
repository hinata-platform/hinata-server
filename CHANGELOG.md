# Changelog

All notable changes to Hivora Server are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-06-11

### Added
- Projects with configurable workflow states and atomic issue numbering
- Issues: types, priorities, tags, subtasks, dependencies, watchers,
  attachments (S3/MinIO), comments, full-text search with pagination
- Agile boards spanning multiple projects, sprints, WIP limits
- Time tracking work items with activity types; timesheet aggregation
- Gantt read model with progress derived from estimates vs. spent time
- Reports: issues by state/priority/assignee, created vs. resolved,
  time per project/activity
- Dashboard aggregation: today's tasks, project completion, 30-day
  performance ranking, 7-day focus tracker
- Knowledge base with hierarchical Markdown articles
- Notifications: persistent in-app + asynchronous HTML e-mails (SMTP/Mailpit)
- E-mail-to-ticket ingestion via IMAP polling
- First-run setup wizard API with optional `HIVORA_SETUP_*` auto-completion
- Runtime admin settings (SSO, e-mail ingest, push) stored in MongoDB,
  hot-reloaded without restart
- SSO: OpenID Connect, OAuth 2.0, SAML 2.0 (IdP metadata), LDAP bind
  authentication; Kerberos/CAS configuration stubs
- Security: HS512 JWT pairs, BCrypt-12, DB-backed login blocking, bucket4j
  rate limiting, hardened headers, strict-by-default authorization
- `/api/v1/meta` version gate and feature flags for the Flutter app
- Docker image (multi-stage, non-root), docker-compose with MongoDB replica
  set (2 nodes + arbiter), MinIO and Mailpit; GitHub Actions CI with GHCR push
