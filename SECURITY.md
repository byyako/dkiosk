# Security

If you find a security problem in dKiosk, such as a way around the settings PIN, the navigation
lock or the API token, please report it privately with
[Report a vulnerability](https://github.com/byyako/dkiosk/security/advisories/new) instead of opening a
public issue. Fixes go into the next release.

Ordinary installs allow Android's Home and Recents controls to leave the app. Administrator PIN
protection is optional. Managed public kiosks can use Android's lock task mode after Device Owner
provisioning or device-manager approval; see [docs/MANAGED-KIOSK.md](docs/MANAGED-KIOSK.md) for its
scope and recovery procedures. Report escapes from an active managed lockdown session privately.
