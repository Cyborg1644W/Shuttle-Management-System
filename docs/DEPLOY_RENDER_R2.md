# Put the shuttle server online with Render and Cloudflare R2

This setup gives both phones the same HTTPS server address. Render runs the Java backend; a private Cloudflare R2 bucket retains its CSV data when Render sleeps or restarts. Your laptop can be off after deployment.

The repository contains the deployment files. It does **not** create accounts, provision a bucket, configure secrets, or deploy the service for you. Complete the checks below before relying on it with drivers.

## 1. Create a private R2 bucket

1. Create or sign in to a [Cloudflare account](https://dash.cloudflare.com/), open **R2 Object Storage**, and activate R2 if requested.
2. Create a **Standard** storage bucket, for example `shuttle-data`. Keep public access disabled: do not enable an `r2.dev` URL or connect a public domain.
3. From R2's API-token management, create an R2 API token with **Object Read & Write** access, scoped to **only this bucket**. This backend needs object access, not permission to administer the whole account.
4. Save the resulting **Access Key ID**, **Secret Access Key**, and S3 endpoint somewhere secure. The endpoint has this form:

   ```text
   https://<your-account-id>.r2.cloudflarestorage.com
   ```

The endpoint is the account's S3 API URL, without the bucket name or an object path. Use the actual endpoint shown by Cloudflare. These credentials belong only in the backend's environment settings, never in the APK, GitHub, a shared invitation, or chat.

For a new system, leave the bucket empty. The server initializes its state object. Do not manually assemble a ZIP or overwrite an existing state object with header-only CSV files.

## Existing local data: import before the first deployment

Skip this section for a new system. If you already have accounts and fleet data locally, import them **before starting the Render server**. The first normal startup creates its initial state object; the importer refuses to replace any object that already exists, even an empty fleet.

1. Stop the local server so the files cannot change during migration. Make a private backup and collect all five comma-separated tables from the same stopped installation in one directory: `workers.txt`, `shuttles.txt`, `shifts.txt`, `schedule.txt`, and `terminals.txt`. For a backup from an older installation, rename the copies from `.csv` to `.txt` before validation, including the optional dated shift table. Do not combine files from different servers or fabricate a missing table. Older data without terminal ownership needs a separate migration; the tool does not guess which operator owns a vehicle or driver.
2. Build the backend image from the repository root:

   ```bash
   docker build -t shuttle-server:local .
   ```

3. Validate the directory without uploading anything. Replace the source path with its absolute local path:

   ```bash
   docker run --rm --user 0:0 \
     --mount type=bind,source=/absolute/private/csv-data,target=/import,readonly \
     --entrypoint java shuttle-server:local \
     -cp /app/shuttle-server.jar com.shuttle.server.storage.StorageAdmin validate /import
   ```

   The one-shot administration container uses UID 0 to read private local files through a read-only mount; the deployed web service continues to run as UID 10001. Successful validation reports the number of CSV tables it checked. Include `dated-shifts.txt` from the same stopped installation if it exists. If validation fails, keep the original data and investigate its schemas and references before uploading.

4. Create a private environment file **outside the Git checkout**, for example `/absolute/private/r2.env`, and restrict access to your own user. Enter your actual R2 settings locally, replacing these placeholders:

   ```dotenv
   STORAGE_MODE=r2
   R2_ENDPOINT=https://<your-account-id>.r2.cloudflarestorage.com
   R2_BUCKET=<your-private-bucket>
   R2_ACCESS_KEY_ID=<your-access-key-id>
   R2_SECRET_ACCESS_KEY=<your-secret-access-key>
   R2_OBJECT_KEY=shuttle-state.zip
   ```

   Do not commit this file or put it in a phone. It contains the same server-only values you will enter in Render.

5. With the Render server still stopped and the selected R2 object absent, import once:

   ```bash
   docker run --rm --user 0:0 \
     --env-file /absolute/private/r2.env \
     --mount type=bind,source=/absolute/private/csv-data,target=/import,readonly \
     --entrypoint java shuttle-server:local \
     -cp /app/shuttle-server.jar com.shuttle.server.storage.StorageAdmin import /import
   ```

   The tool validates, conditionally creates the full snapshot, reads it back, and verifies the exact bytes. It refuses an existing object; it has no overwrite flag. If a connection fails after an upload, inspect the private object and retain your backup before trying again. Do not delete cloud state just to bypass the refusal.

6. After the tool reports a verified upload, deploy with the same bucket and object key below. Keep the local server stopped when changing the phones to the Render address.

## 2. Deploy the repository on Render

1. Push the reviewed changes to your GitHub repository.
2. Create or sign in to [Render](https://dashboard.render.com/). Choose **New → Blueprint**, connect the repository, and select the `Backend` branch containing the dated shift handler and this Blueprint.
3. Review the proposed service. It must be a **Free** Docker web service with **one instance**, health-check path `/api/health`, and **no persistent disk or paid database**. Do not add autoscaling or another server against the same R2 object.
4. Fill the four requested environment settings:

   | Setting | Value to enter privately in Render |
   | --- | --- |
   | `R2_ENDPOINT` | The HTTPS S3 endpoint from Cloudflare |
   | `R2_BUCKET` | Your private bucket's exact name |
   | `R2_ACCESS_KEY_ID` | The bucket-scoped Access Key ID |
   | `R2_SECRET_ACCESS_KEY` | The matching Secret Access Key |

5. Apply the Blueprint and wait for the service to build and start. The Docker build compiles only `common` and `server-backend`; it does not install Android tools or use the checked-in Windows SDK path.
6. Copy the service's HTTPS address from Render, such as `https://your-shuttle-server.onrender.com`. Open `/api/health` on that address. A healthy response is HTTP 200 with:

   ```csv
   status
   ok
   ```

If the build or startup fails, read the Render deployment logs before creating any accounts. Verify that `STORAGE_MODE` is `r2` and all four R2 values are present. Do not change it to `local` to make a cloud deployment appear healthy: Render's free filesystem is temporary.

The included Blueprint sets these non-secret values:

| Setting | Value | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP listen port; Render provides external HTTPS |
| `DATA_DIR` | `/app/data` | Temporary runtime directory, writable by the non-root container user; cloud CSV state stays in R2 |
| `STORAGE_MODE` | `r2` | Require durable R2 storage |
| `R2_OBJECT_KEY` | `shuttle-state.zip` | The private object containing the full CSV state |

If you create a Web Service manually instead of using the Blueprint, select the `Backend` branch and use Docker, the repository root as build context, `Dockerfile` as the Dockerfile path, `/api/health` as the health check, the Free plan, and **all** the environment variables above. No custom start command is needed.

## 3. Connect the phones

1. Install the updated Android APK on both phones.
2. Check the address shown on the sign-in screen. Fresh installs and upgrades from 1.0 use **Automatic**, which points to `https://shuttle-server-wh1z.onrender.com/api` in the release app. If you deployed a different service, select **Server connection → Custom server**, enter or reuse its address, and save. Both phones must use the same service.
3. On the operator's phone, register an **Operator** account, sign in, and create the terminal.
4. On the driver's phone, register a **Driver** account and sign in. It starts without a terminal.
5. Operator: choose **Invite drivers**, generate a code, and share it through Messenger or SMS.
6. Driver: choose **Join terminal**, enter the code, check the terminal/operator details, and confirm. The driver should appear in the operator's list after refresh.

Each terminal has one current invitation. It works once while enabled and has no time limit. A driver has one current terminal; accepting another terminal's invitation switches membership and clears the old shuttle assignment and schedule. The driver must finish an active shift before switching.

Use mobile data on at least one phone to check that the two phones work from different networks.

## 4. Prove that data survives a restart

Do these checks with the laptop's local server stopped:

1. Complete the operator/driver invitation flow above. Record the terminal name and confirm the operator sees the driver.
2. Create another invitation and leave it unused.
3. Confirm the private R2 bucket contains `shuttle-state.zip` (or the configured `R2_OBJECT_KEY`). Keep the object private.
4. Restart the Render service from its dashboard. Live login sessions are held in memory, so sign in again on both phones after the restart.
5. Confirm both accounts still work, the driver's terminal membership is unchanged, and the operator's list still contains the driver.
6. Use the previously unused invitation with another driver account. Confirm it works once. Confirm an already-used invitation is rejected.

An open port, a successful Docker build, or the presence of an R2 object alone does not prove this workflow. Live Render/R2 validation is complete only after the remote two-client and restart checks pass.

## Storage, backups, and limits

The server saves `workers.txt`, `shuttles.txt`, `shifts.txt`, `schedule.txt`, and `terminals.txt` together in one private ZIP snapshot, with an automatically generated `commit.txt` marker. The optional `dated-shifts.txt` joins the same snapshot after a dated assignment is saved. The current server reads snapshots with or without that table. Deploy the scheduling-capable server before updating the Android clients. This build reads existing snapshots with `.csv` entries. The next successful data change writes `.txt` entries. Older builds require the old filenames, so keep a private copy of the original snapshot before upgrading. R2 conditional writes compare the object's ETag, preventing two running instances from silently overwriting one another's state. The backend refreshes cloud state before handling a request and checks storage as part of readiness. Keep exactly one instance. If storage cannot be read, written, or safely updated, investigate the error; do not overwrite the object or start a second writer as a workaround. The prototype limits both the snapshot and its expanded contents to 32 MiB; it is not intended as an unlimited history archive.

A failed or uncertain write can return HTTP 503 or 409. Refresh to inspect the saved result before submitting the action again; do not automatically replay a registration, invitation redemption, or other state-changing request after a timeout.

Treat the snapshot as private account and operations data. It contains password hashes, worker details, terminal memberships, invitations, vehicles, schedules, and shift history. Live login tokens and live GPS positions are in memory and are not restored by a restart. This setup does not add automatic historical backups. Before an intentional migration or data-recovery operation, stop writes and download a dated private backup of the **whole** snapshot from R2. Preserve it securely; do not back up only `workers.txt` or manually edit the ZIP to recover a terminal.

If you already have local CSV data, use the create-only import procedure above before the first hosted startup. Never point a fresh empty deployment at a populated bucket with the intention of replacing its data. Data migration is a separate operation from creating a new fleet.

Render's free service can sleep when idle, making the first connection slow. Sign-in, registration and fleet requests wait up to 90 seconds using safe health checks before submitting the action. The app never automatically repeats a state-changing request. If it still times out, refresh to inspect the result before submitting again. Free compute and R2 have usage limits. They are suitable for a small trial, not a promise of uninterrupted production tracking. Review the current [Render free-service limits](https://render.com/docs/free) and [R2 pricing/free allowances](https://developers.cloudflare.com/r2/pricing/) before activation; Cloudflare may request a billing profile, and usage above its allowance can cost money. Do not select paid resources if your requirement is zero hosting spend.

## Optional local container check

With Docker installed, build from the repository root:

```bash
docker build -t shuttle-server:local .
docker run --rm --name shuttle-local -p 8080:8080 -e STORAGE_MODE=local shuttle-server:local
```

From another terminal, check:

```bash
curl --fail http://127.0.0.1:8080/api/health
```

This uses local, temporary container storage and checks packaging/startup only. It does not validate R2 or a live Render deployment. Stop the named test container when finished. The runtime image runs as UID/GID `10001`; any local directory mounted as `DATA_DIR` must be writable by that user.

The Java base images are pinned to verified Temurin 21 image digests. Refresh and retest those pins when applying Java security updates.
