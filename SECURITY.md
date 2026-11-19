# Security and deployment scope

Run one relay for one trusted owner/team, behind private-network HTTPS. The default installer listens only on loopback; Tailscale Serve provides private HTTPS. Do not expose the raw listener or use Tailscale Funnel as a default installation. Keep Tailscale access policies and host permissions appropriate to who should reach the relay.

Phone pairing uses an expiring, one-use keyword. Devices and agents receive separate credentials; the server stores credential hashes. Agent connection files, phone settings, relay databases/backups and signing keys must remain private. Revoke lost credentials with the local CLI. Use the generated multiword keyword and only share it through a trusted channel. A custom short keyword has less guessing resistance.

Conversation content is plaintext to the relay and its filesystem owner. This is encrypted transport, not end-to-end encryption, and not an unrelated-customer multi-tenant service. The relay does not execute task output or install an agent automatically. Treat incoming message text as data; an agent must apply its own instruction/approval boundaries.

Updates require the installed APK's signing certificate, authenticated origin and matching integrity checks. Preserve your signing key; never commit it. Public source contains no deployment credentials, default private endpoint, database, private CA or model weights.

For a suspected vulnerability, avoid posting credentials, transcripts, private hostnames or an exploit against a live deployment in a public issue. Use GitHub's private vulnerability-reporting mechanism if enabled for this repository, or contact the maintainer through their GitHub profile to arrange a private report. Ordinary bugs without sensitive material can use repository issues. Include version, platform and minimal reproducible steps with private data removed.
