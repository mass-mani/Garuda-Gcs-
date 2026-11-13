#include "GarudaVehicleDirectoryTest.h"

#include "GarudaVehicleDirectory.h"
#include "GarudaSwarmController.h"
#include "MultiVehicleManager.h"
#include "Vehicle.h"

#include <QtCore/QSettings>
#include <QtCore/QUuid>
#include <QtTest/QSignalSpy>
#include <QtTest/QTest>

void GarudaVehicleDirectoryTest::_aliasesAndSelection()
{
    _connectMockLink();

    QVERIFY(_vehicle);
    GarudaVehicleDirectory directory;
    const QString settingKey = QStringLiteral("Garuda/Vehicles/sysid-%1/name").arg(_vehicle->id());
    QSettings settings;
    const bool hadStoredName = settings.contains(settingKey);
    const QVariant oldStoredName = settings.value(settingKey);

    const QString defaultName = QStringLiteral("GARUDA-%1").arg(_vehicle->id(), 2, 10, QLatin1Char('0'));
    settings.remove(settingKey);
    QCOMPARE(directory.name(_vehicle), defaultName);
    QCOMPARE(directory.componentId(_vehicle), _vehicle->defaultComponentId());
    QVERIFY(!directory.connection(_vehicle).isEmpty());
    QVERIFY(!directory.identityConflict(_vehicle)); // MockLink does not advertise a hardware UID.

    QSignalSpy changedSpy(&directory, &GarudaVehicleDirectory::changed);
    QVERIFY(changedSpy.isValid());
    QVERIFY(!directory.rename(_vehicle, QString()));
    QVERIFY(!directory.rename(_vehicle, QStringLiteral("   ")));
    QVERIFY(!directory.rename(_vehicle, QString(65, QLatin1Char('x'))));
    QVERIFY(directory.rename(_vehicle, QStringLiteral("  Test aircraft  ")));
    QCOMPARE(directory.name(_vehicle), QStringLiteral("Test aircraft"));
    QCOMPARE(changedSpy.count(), 1);

    QVERIFY(directory.select(_vehicle));
    QCOMPARE(MultiVehicleManager::instance()->activeVehicle(), _vehicle);

    if (hadStoredName) settings.setValue(settingKey, oldStoredName);
    else settings.remove(settingKey);
    settings.sync();
}

void GarudaVehicleDirectoryTest::_groupsAndExactSelection()
{
    _connectMockLink();
    QVERIFY(_vehicle);

    GarudaSwarmController swarm;
    const QString name = QStringLiteral("test-%1").arg(QUuid::createUuid().toString(QUuid::WithoutBraces));
    QVERIFY(swarm.createGroup(name));
    QVERIFY(!swarm.createGroup(name.toUpper()));
    QCOMPARE(swarm.selectedGroup(), name);
    QVERIFY(swarm.setMember(name, _vehicle, true));
    QVERIFY(swarm.containsVehicle(name, _vehicle));
    QVERIFY(swarm.activateGroup(name));
    QVERIFY(swarm.enabled());
    QCOMPARE(swarm.activeGroup(), name);
    QCOMPARE(MultiVehicleManager::instance()->selectedVehicles()->count(), 1);

    // Editing active membership must invalidate the old exact-selection state.
    QVERIFY(swarm.setMember(name, _vehicle, false));
    QVERIFY(!swarm.enabled());
    QVERIFY(swarm.activeGroup().isEmpty());
    QCOMPARE(MultiVehicleManager::instance()->selectedVehicles()->count(), 0);
    QVERIFY(swarm.setMember(name, _vehicle, true));
    QVERIFY(swarm.activateGroup(name));

    swarm.disableGroup();
    QVERIFY(!swarm.enabled());
    QVERIFY(swarm.activeGroup().isEmpty());
    QCOMPARE(MultiVehicleManager::instance()->selectedVehicles()->count(), 0);
    QVERIFY(swarm.deleteGroup(name));
}
