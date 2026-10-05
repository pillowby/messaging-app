# Extra CA certificates (optional)

Any `*.crt` / `*.pem` file in this folder is added to the JDK trust store **in the Docker build
stage only**, so Maven can download dependencies from behind a TLS-inspecting proxy
(e.g. Cloudflare WARP / Zero Trust, Zscaler, corporate firewalls).

On a normal network leave this folder empty; the build doesn't need it.
Certificate files are git-ignored because they are specific to one machine/network.
