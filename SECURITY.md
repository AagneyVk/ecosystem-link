# Security Policy

## Supported versions

This project is pre-1.0. Security fixes are applied to the latest commit on the default branch only.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting feature for this repository. Do not open a public issue for vulnerabilities or include live credentials, private addresses, device identifiers, recordings, clipboard contents, or personal files in a report.

Include a concise impact statement, affected component, reproduction steps using synthetic data, and any suggested mitigation. You should receive an acknowledgement within seven days.

## Deployment boundary

Ecosystem Link is intended for devices owned and controlled by the same user over a trusted VPN. Do not expose its cleartext HTTP or WebSocket listeners to the public internet. Bind remote-facing services to an explicit VPN address, restrict ports with a firewall, and keep the admin/UI services local. Rotate any shared secret if it may have been exposed.

The project does not bypass Android runtime permissions, foreground-service requirements, MediaProjection consent, or secure-content restrictions. Reports requesting such bypasses are out of scope.

