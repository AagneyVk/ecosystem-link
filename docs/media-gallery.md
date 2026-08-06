# Media gallery

`MediaIndex` derives an index from image/video files below the configured storage root. Opaque IDs are hashes of root-relative paths; clients never submit filesystem paths. Content and deletion APIs resolve only indexed IDs. Peer metadata can enrich lens/burst fields but cannot alter the indexed path.

The gallery sorts newest first and lazy-loads images. The viewer preserves aspect ratio and supports fit, zoom, rotation, previous/next keyboard navigation, metadata, and confirmed deletion. Video items use the browser player/content endpoint. Index scanning and hashing run outside the aiohttp event loop.

Manual test: take a snapshot, wait for verified upload, open Camera, select its thumbnail, exercise zoom/rotate/arrows, then delete a disposable image and confirm it disappears.
