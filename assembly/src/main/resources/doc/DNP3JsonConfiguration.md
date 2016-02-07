
# Frontend Json Configuration

Full example:

```json
{
  "amqpConfig" : {
    "amqpConfigFileList" : [ "etc/io.greenbus.msg.amqp.cfg" ],
    "failureLimit" : 3,
    "retryDelayMs": 5000,
    "connectionTimeoutMs": 10000
  },
  "endpointWhitelist" : ["Endpoint01"],
  "nodeId" : "NodeA",
  "userConfigPath" : "etc/io.greenbus.user.cfg",
  "registrationConfig" : {
    "loginRetryMs" : 5000,
    "registrationRetryMs" : 5000,
    "releaseTimeoutMs" : 60000,
    "statusHeartbeatPeriodMs" : 5000,
    "lapsedTimeMs" : 11000,
    "statusRetryPeriodMs" : 2000,
    "measRetryPeriodMs" : 2000,
    "measQueueLimit" : 1000,
    "configRequestRetryMs" : 5000
  }
}
```

## Parameters

- `endpointWhitelist`: Array of endpoint names to provide service for. To serve any endpoint, remove this parameter.
- `nodeId`: Name of frontend node. Identifies the same process across restarts, enabling re-registration without waiting
for the measurement processor to open for failover. Remove this parameter unless it can be guaranteed to be unique across
frontend processes in the system.
- `userConfigPath`: Path for user configuration file for credentials to login to the services.

### AMQP Config

- `amqpConfigFileList`: Array of AMQP configuration files describing broker addresses to connect to.
- `failureLimit`: Number of times connection attempts using an AMQP configuration file in the list can fail before the
next configuration is attempted.
- `retryDelayMs`: Delay between connection retry attempt, in milliseconds.
- `connectionTimeoutMs`: Time to wait for a connection to be successful, in milliseconds.

### Registration Config

- `loginRetryMs`: Delay between login attempts, in milliseconds.
- `registrationRetryMs`: Delay between registration attempts, in milliseconds.
- `releaseTimeoutMs`: Time in milliseconds to hold the external protocol connection while the services connection is lost.
- `statusHeartbeatPeriodMs`: Delay between sending heartbeats, in milliseconds.
- `lapsedTimeMs`: Time, in milliseconds, since the last successful measurement publish or heartbeat after which to consider
the services connection lost and attempt re-registration.
- `statusRetryPeriodMs`: Delay between retrying heartbeats, in milliseconds.
- `measRetryPeriodMs`: Delay between retrying measurement publish attempts, in milliseconds.
- `measQueueLimit`: Number of measurements to queue while the service connection is lost but the protocol connection is active.
- `configRequestRetryMs`: Delay between configuration attempts, in milliseconds.

