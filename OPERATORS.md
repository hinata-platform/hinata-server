# Running Hinata: content safety for operators

**As of 6 August 2026.** Everything below about law and app-store policy is a
pointer to something you must check yourself, not advice, and several of these
areas changed within months of this being written. Re-check before you rely on
any of it.

This document is for whoever actually runs a Hinata server — a student union, a
department, a small company. It assumes no trust-and-safety background. It is
short on purpose; if you read one section, read [When the escalation fires](#5-when-the-escalation-fires).

---

## 1. You are the hosting provider

This is the part people get wrong, so it comes first.

When someone uploads a file to your Hinata instance, **you** store it. Not the
Hinata project, not whoever wrote the code. Obligations about hosted content
attach to whoever does the hosting, and that is you. The software can give you
tools; it cannot take on your position.

Practically: if something illegal is uploaded to your server, the questions that
follow — who noticed, what did you do, who did you tell — are questions about
your organisation.

---

## 2. What is switched on, and what is not

| | Default | What it does |
|---|---|---|
| Text filter | **on** | German + English word list over issue titles, descriptions, comments, articles, project and team names. Deterministic — no model, no network. |
| Reporting | **on** | Any user can report content and report another user. |
| Blocking | **on** | Any user can block another user; blocked people's comments stop appearing for them. |
| Moderation queue | **on** | Admin area → Moderation. Flagged content and user reports. |
| **Image classification** | **off** | Nothing ships that looks at pixels. See §3. |
| **Known-illegal hash matching** | **off, and no implementation exists** | See §4. |
| Voice notes | **not checked at all** | Recorded audio is stored and served unexamined. |
| PDFs and Office documents | **not checked** | Type and file signature are verified; the contents are not read. |

Two things follow that are easy to misread.

**The text filter is deliberately reluctant.** Long text — a description, a
comment, an article — is queued for a human rather than refused, however badly it
scores. A wrongly refused bug report does not get rewritten; it gets abandoned, or
moved to a chat nobody moderates. Short fields that everyone sees, like a project
name, are refused outright. You can change this (`longFormFlagOnly`), but consider
why it is set the way it is first.

**Two categories cannot be weakened by any setting**: child sexual content and
malware. There is no admin control for them, by construction.

Why the unchecked rows are unchecked — voice, PDF contents, video, and the missing
statement of reasons on a freeze — is set out with its evidence in *Why these gaps
exist* in the setup guide. None of them is an oversight; the reasons differ in kind,
and one of them is legal rather than technical.

---

## 3. Turning on image classification

Without this, uploaded images are checked for type and size and nothing else.

The classifier runs as a separate container so that installs which never receive
an image do not carry a few hundred megabytes of model. Start the service:

> There is an illustrated step-by-step version of this section, with diagrams of
> the upload path and the four tier states:
> [`docs/moderation-setup-en.pdf`](docs/moderation-setup-en.pdf) ·
> [`docs/moderation-setup-de.pdf`](docs/moderation-setup-de.pdf)

```yaml
# docker-compose.override.yml — a ready-made fragment ships in hinata-moderation/
services:
  hinata-moderation:
    image: ghcr.io/hinata-platform/hinata-moderation:latest
    read_only: true
    security_opt: ["no-new-privileges:true"]
    cap_drop: ["ALL"]
```

…then point Hinata at it, either way round:

- **Admin area → Moderation → Image classifier.** Paste the address, save. It takes
  effect on the next upload; no restart, and the status line above it changes as
  soon as the server can reach the sidecar.
- Or `HINATA_MODERATION_IMAGE_ENDPOINT: http://hinata-moderation:8081` in the
  server's environment, which is the default the panel falls back to when its field
  is left empty. An address entered in the panel wins over this one.

**Then verify it is actually classifying**, because "the container is running" and
"images are being checked" are different facts:

1. Admin area → Moderation. The line under the *classify images* switch says which
   of four states you are in. `Active` is the only one that means images are being
   examined.
2. The server logs a `WARN` at startup when image moderation is enabled and no
   classifier is installed, and an `INFO` naming every address it is pointed at.

If you decide not to run it, switch *classify images* off (or set
`HINATA_MODERATION_IMAGE_ENABLED=false`) so the panel says so plainly rather than
implying a check that is not happening.

Which content types the sidecar is sent is **not** a panel setting: that list has to
match what the deployed sidecar build can decode, so it stays with the deployment
(`hinata.moderation.image.supported-types`). Adding a type the sidecar cannot read
would not teach it to; it would only turn an honest "not judged" into a failed round
trip on every such upload.

---

## 4. Hash matching for known illegal material

Hinata has a slot for this and **ships no implementation, and never will.**

That is not laziness. These programmes vet the *operator organisation*, not the
software; their terms forbid passing credentials on; and their hash lists live on
their servers precisely so that nobody can hold a local copy and test against it
to find out what evades detection. A version bundled with the software would be
useless or dangerous, usually both.

If your instance is exposed to material from outside your organisation — an
inbound e-mail address that creates tickets, for example — applying is worth it.
Expect the vetting to take weeks.

- Microsoft **PhotoDNA Cloud Service** — <https://www.microsoft.com/photodna>
- **Thorn Safer** — <https://safer.io>
- Google **Child Safety Toolkit** — <https://protectingchildren.google>
- Cloudflare **CSAM Scanning Tool** — <https://developers.cloudflare.com/cache/reference/csam-scanning/>
  (note: this inspects content served through Cloudflare's cache, so private
  attachments streamed from your own object storage may be invisible to it —
  confirm coverage before treating it as protection)

The credentials you receive are yours and are not transferable. Once you have
them, the integration is a small adapter against the existing port; ask whoever
maintains your deployment.

---

## 5. When the escalation fires

Configure a webhook so you find out immediately rather than the next time someone
opens the admin panel. Admin area → Moderation → Escalation, or:

```sh
HINATA_MODERATION_ESCALATION_URL=https://…      # your alerting endpoint
HINATA_MODERATION_ESCALATION_SECRET=…           # verify X-Hinata-Signature (HMAC-SHA256)
```

Both halves are required and the address is refused without a secret — from the
panel with an error, and by the adapter, which will not deliver unsigned. A notice
claiming content was frozen that its recipient cannot attribute is worse than no
notice at all. The secret is write-only: the panel accepts a new one and never
shows you the stored one back, so leaving the field empty keeps what is already
there.

The payload carries a record id, a category, a surface and a timestamp. **No
content, no file name, no bytes.** That is deliberate: the alert travels further
than the content ever should.

### The runbook

> **Do not open the content to check.**
>
> This is the single most important line in this document. Looking is not
> verification — it does not make the report more accurate, and for one category
> it is itself the harm. The software will not show it to you either: frozen
> content is unreachable through the interface for administrators as well.

1. **Freeze.** An urgent report freezes automatically. If you are acting on
   something else, freeze it from the moderation queue. Freezing hides it; it does
   not delete it.
2. **Do not delete.** Deletion paths refuse frozen content on purpose. Destroying
   material before you have met a reporting obligation is its own problem, and it
   is not yours to weigh in the moment.
3. **Escalate to the person you named in advance** (§7). Not to the team channel.
4. **That person contacts the authority**, per your own procedure. Hinata does not
   report to anyone on your behalf — it does not know your jurisdiction and is not
   the reporting party. In Germany the BKA operates a central reporting portal;
   confirm the current route for your own case.
5. **Write down what you did and when.** Time-to-action is the thing you will be
   asked about.
6. Unfreeze only after a deliberate decision, and only through the audited
   unfreeze — it is recorded.

---

## 6. Questions to answer about your own instance

Phrased as questions because the answers depend on how *your* instance is
configured and on law that moves. A document that stated them as settled would be
wrong for some operators immediately and for all of them eventually.

**Am I a hosting service or an online platform?** The distinction turns on whether
content is disseminated to the public. A closed, authenticated, single-organisation
instance is generally not the latter, which leaves a whole tier of obligations
(complaint handling, out-of-court dispute settlement, transparency reporting) out
of scope. Enabling public share links, a public knowledge base or a public issue
portal can change the answer. *Hinata has a test that fails if a new
unauthenticated route is added, precisely so this question is re-opened
deliberately rather than by accident.*

**What applies regardless?** Notice-and-action obligations, and a statement of
reasons for restrictions, apply to hosting providers without a size threshold.
Hinata's refusal dialog is built to serve the latter: it names the category, says
the check was automated, and points at a human. **Note what it does not do: a
freeze issues no statement of reasons.** That gap is recorded in the data model
rather than filled — if it matters for you, it is a manual step in your procedure.

**Where do I report a suspected offence?** Establish this before you need it.
Member states differ, and the route for a German operator is not the route for
someone hosting elsewhere.

**What does data-protection law require of me?** At minimum: a legal basis for
scanning content at all, an impact assessment (systematic automated assessment of
communications is close to the line), a processing agreement with any classifier
you send content to, and human review where an automated decision has real
consequences. Running the classifier as your own container rather than calling a
cloud API removes the transfer question entirely — that is part of why it is
shaped that way.

**Does scanning voice notes or private messages need a different basis than file
attachments?** Possibly, and this area is actively moving. The distinction is
between hosting and interpersonal communication, and it is legal rather than
technical. Hinata does not currently examine voice notes at all, so the question
is not yet live for you — but it becomes live the day that changes.

In every case: you decide and you file. Not Hinata.

---

## 7. Before you go live

Write your escalation procedure down and **name the person**. One named human,
with a deputy. "The admins" is not an answer at 23:00 on a Friday.

The procedure should say: who is called, in what order, who contacts the
authority, where the record is kept, and — explicitly — that nobody opens the
content to check.

Consider also what support that person gets. Being the named contact for this is
not a neutral administrative duty, and organisations that plan for it in advance
handle it better than those that discover it during an incident.

---

## 8. App stores

If you distribute a build of the Hinata app yourself, Apple's and Google's
user-generated-content rules apply to you as the publisher. Both expect a
filtering mechanism, in-app reporting of content **and of users**, user blocking,
published contact information, and terms the user accepts before creating content.
Hinata implements all of these; whether your configuration and your store listing
satisfy the reviewer is a separate question.

Age ratings and platform policies are snapshots. Both stores revise them, and the
answer that was true at your last submission may not be true at your next one.
Check the current wording each time rather than carrying forward a previous
answer — including the age-rating questionnaire, where a careless answer about
embedded browsing has more effect on the resulting rating than the presence of
user-generated content does.
