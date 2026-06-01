# XMage Build Notes

## Build Command (from XMage source/README)

XMage uses a standard Maven build. The recommended command (skipping tests for initial build):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export MAVEN_OPTS="-Xmx4g"
mvn -DskipTests clean install
```

For parallel build (faster, if modules allow):
```bash
mvn -DskipTests -T 4 clean install
```

## Environment

- **Java**: OpenJDK 17.0.19 (arm64, Homebrew) — XMage pom.xml targets `java.version=1.8` source level; Java 17 LTS is compatible
- **Maven**: 3.9.16
- **OS**: macOS (aarch64)
- **Note**: Maven's default JAVA_HOME resolves to Java 26 from PATH. Must explicitly set `JAVA_HOME` to Java 17.

## Test Command

```bash
mvn test -pl Mage.Tests -Dtest=EquipAbilityTest -DfailIfNoTests=false
```
