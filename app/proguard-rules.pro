# Components named in the manifest (activities, services, the tile and the
# accessibility service) are kept automatically by R8.
# Nothing in QuickRecord uses reflection, so no extra rules are needed.

# Keep readable stack traces in the problems log.
-keepattributes SourceFile,LineNumberTable
