# Internal Database API

Shared contract for feature modules to access allowlist and report threats.

## Shared Files

| File | Purpose |
|------|---------|
| `AntiCenterAPI.kt` | Core interface with data access methods |
| `AntiCenterSDK.kt` | SDK singleton for getting API instance |
| `FeatureDomain.kt` | Feature domain enumeration |


## API Functions

### `suspend fun isInAllowlist(value: String, featureType: FeatureDomain): Boolean`

Checks if a value exists in the allowlist for the specified feature domain.

**Parameters:**
- `value`: The value to check (phone number, email, URL, etc.)
- `featureType`: Feature domain (`FeatureDomain.CALL`, `FeatureDomain.EMAIL`, etc.)

**Returns:** `true` if value is in allowlist, `false` otherwise

**Example:**
```kotlin
val api = AntiCenterSDK.getAPI()
val isAllowed = api.isInAllowlist("+8613012345678", FeatureDomain.CALL)
```

### `suspend fun reportThreat(threatType: String, source: String, status: String, featureType: FeatureDomain, additionalInfo: String = ""): Result<Unit>`

Reports a detected threat or suspicious event.

**Parameters:**
- `threatType`: Category of threat (e.g., "Suspicious Call", "Phishing Email")  
- `source`: Origin of threat (phone number, email, URL, etc.)
- `status`: Status label ("detected", "suspicious", "blocked")
- `featureType`: Feature domain that detected the threat
- `additionalInfo`: Optional extra context

**Returns:** `Result.success(Unit)` on success, `Result.failure(Exception)` on error

**Example:**
```kotlin
val api = AntiCenterSDK.getAPI()
api.reportThreat(
    threatType = "Suspicious Call",
    source = "+8613012345678", 
    status = "detected",
    featureType = FeatureDomain.CALL,
    additionalInfo = "riskScore=0.82"
).onFailure { error ->
    // Handle error
}
```

## Initialization

Initialize once in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val impl = AntiCenterAPIImpl.getInstance(this)
        AntiCenterSDK.setImplementation(impl)
    }
}
```

## FeatureDomain Values

| Value | Description |
|-------|-------------|
| `FeatureDomain.CALL` | Call protection |
| `FeatureDomain.MEETING` | Meeting protection |
| `FeatureDomain.URL` | URL protection |
| `FeatureDomain.EMAIL` | Email protection |


```mermaid
sequenceDiagram
    
    participant FM as Feature Module
    participant SDK as AntiCenterSDK
    participant API as AntiCenterAPI (interface)
    participant Impl as AntiCenterAPIImpl (internal)
    participant DB as DatabaseManager (internal)
    participant SQL as SQLite DB

    FM->>SDK: getAPI()
    SDK-->>FM: AntiCenterAPI

    FM->>API: isInAllowlist(value, FeatureDomain)
    API->>Impl: (dispatch)
    Impl->>Impl: FeatureDomain -> SelectFeatures (map)
    Impl->>DB: isValueInAllowlist(value, SelectFeatures)
    DB->>SQL: SELECT ... FROM allowlist ...
    SQL-->>DB: rows / empty
    DB-->>Impl: Boolean
    Impl-->>FM: Boolean

    rect rgba(200,200,255,0.15)
    note over FM,Impl: reportThreat(...) 类似链路\nImpl 组装 AlertLogItem -> DB.insertAlertLogItem
    end
```

```plantuml
@startuml
!theme plain

package "Public API" {
  class FeatureModule
  class AntiCenterSDK {
    +getAPI(): AntiCenterAPI
    +setImplementation(impl: AntiCenterAPI)
    +isInitialized(): Boolean
  }
  interface AntiCenterAPI {
    +isInAllowlist(value: String, featureType: FeatureDomain): Boolean
    +reportThreat(threatType: String, source: String, status: String, featureType: FeatureDomain, additionalInfo: String): Result<Unit>
  }
  enum FeatureDomain {
    CALL
    MEETING
    URL
    EMAIL
  }
}

package "Internal UI" {
  class AllowlistViewModel {
    +loadAllowlistItems(featureType: SelectFeatures)
    +getAllowlistItems(featureType: SelectFeatures): List<AllowlistItem>
    +addAllowlistItem(item: AllowlistItem, featureType: SelectFeatures)
    +updateAllowlistItem(item: AllowlistItem, featureType: SelectFeatures)
    +deleteAllowlistItemsByFeature(featureType: SelectFeatures)
    +checkValueInAllowlist(value: String, featureType: SelectFeatures)
  }
  class AlertLogViewModel {
    +loadAlertLogs(featureType: SelectFeatures)
    +getAlertLogItems(featureType: SelectFeatures, limit: Int, offset: Int): List<AlertLogItem>
    +addAlertLog(item: AlertLogItem, featureType: SelectFeatures)
    +cleanupOldAlertLogs(featureType: SelectFeatures, keepCount: Int)
    +clearAlertLogsByFeature(featureType: SelectFeatures)
    +getAlertLogCount(featureType: SelectFeatures): Int
  }
}

package "Internal Core" {
  class AntiCenterAPIImpl {
    +isInAllowlist(value: String, featureType: FeatureDomain): Boolean
    +reportThreat(threatType: String, source: String, status: String, featureType: FeatureDomain, additionalInfo: String): Result<Unit>
    -toInternal(featureType: FeatureDomain): SelectFeatures
  }
  enum SelectFeatures {
    callProtection
    meetingProtection
    urlProtection
    emailProtection
  }
  class DatabaseManager {
    +isValueInAllowlist(value: String, featureType: SelectFeatures): Boolean
    +insertAllowlistItem(item: AllowlistItem, featureType: SelectFeatures): Long
    +getAllowlistItems(featureType: SelectFeatures): List<AllowlistItem>
    +updateAllowlistItem(item: AllowlistItem, featureType: SelectFeatures): Int
    +deleteAllowlistItemsByFeature(featureType: SelectFeatures): Int
    +insertAlertLogItem(item: AlertLogItem, featureType: SelectFeatures): Long
    +getAlertLogItems(featureType: SelectFeatures): List<AlertLogItem>
    +getAlertLogItems(featureType: SelectFeatures, limit: Int, offset: Int): List<AlertLogItem>
    +cleanupOldAlertLogs(featureType: SelectFeatures, keepCount: Int): Int
    +getAlertLogCount(featureType: SelectFeatures): Int
  }
  class SQLiteDB <<database>> {
    allowlist_callProtection
    allowlist_meetingProtection
    allowlist_urlProtection
    allowlist_emailProtection
    alert_log_callProtection
    alert_log_meetingProtection
    alert_log_urlProtection
    alert_log_emailProtection
  }
}

FeatureModule --> AntiCenterSDK : getAPI()
FeatureModule --> AntiCenterAPI : isInAllowlist()
FeatureModule --> AntiCenterAPI : reportThreat()
AntiCenterSDK --> AntiCenterAPI
AntiCenterAPI <|.. AntiCenterAPIImpl : implements

AntiCenterAPIImpl --> SelectFeatures : maps FeatureDomain
AntiCenterAPIImpl --> DatabaseManager : delegates

AllowlistViewModel --> DatabaseManager : CRUD operations
AlertLogViewModel --> DatabaseManager : CRUD operations

DatabaseManager --> SQLiteDB : SQL queries

@enduml
```
