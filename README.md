Ehcache File Monitor
====================

Ehcache File Monitor attempts to provide a scalable, high performance solution
for loading resources from a file system while ensuring the "freshness" of the
loaded resources.

What It Does
------------

The concept is simple: Resources are loaded from the file system as they are
requested.  Loaded resources are cached as long as they are being actively
requested, allowing for high scalability.  Callers don't have to wait for the
resource to be loaded from the file system again, if the underlying file hasn't
changed.  When a file has changed, the resource which was loaded from that file
is removed from the cache, such that it will be loaded from the updated file
the next time it is requested.