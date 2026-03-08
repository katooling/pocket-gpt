package android.net

import android.os.Parcel

class TestUri(
    private val raw: String = "content://pocketagent/test",
) : Uri() {
    override fun buildUpon(): Builder = throw UnsupportedOperationException("TestUri")

    override fun getAuthority(): String = "pocketagent"

    override fun getEncodedAuthority(): String = "pocketagent"

    override fun getEncodedFragment(): String? = null

    override fun getEncodedPath(): String = "/test"

    override fun getEncodedQuery(): String? = null

    override fun getEncodedSchemeSpecificPart(): String = raw

    override fun getEncodedUserInfo(): String? = null

    override fun getFragment(): String? = null

    override fun getHost(): String = "pocketagent"

    override fun getLastPathSegment(): String = "test"

    override fun getPath(): String = "/test"

    override fun getPathSegments(): List<String> = listOf("test")

    override fun getPort(): Int = -1

    override fun getQuery(): String? = null

    override fun getScheme(): String = "content"

    override fun getSchemeSpecificPart(): String = raw

    override fun getUserInfo(): String? = null

    override fun isHierarchical(): Boolean = true

    override fun isRelative(): Boolean = false

    override fun toString(): String = raw

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
}
