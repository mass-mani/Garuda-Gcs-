#include "GarudaVehicleDirectory.h"
#include "Vehicle.h"
#include "MultiVehicleManager.h"
#include "VehicleLinkManager.h"
#include "LinkInterface.h"
#include "QmlObjectListModel.h"
#include <QSettings>
#include <QMetaEnum>

GarudaVehicleDirectory::GarudaVehicleDirectory(QObject *parent) : QObject(parent)
{
    auto update = [this] { ++_revision; emit changed(); };
    connect(MultiVehicleManager::instance(), &MultiVehicleManager::vehicleAdded, this, update);
    connect(MultiVehicleManager::instance(), &MultiVehicleManager::vehicleRemoved, this, update);
}

bool GarudaVehicleDirectory::current(Vehicle *vehicle) const
{
    return vehicle && MultiVehicleManager::instance()->getVehicleById(vehicle->id()) == vehicle;
}

QString GarudaVehicleDirectory::key(Vehicle *vehicle) const
{
    // Hardware UID is preferred. Without one, aliases are explicitly local SYSID labels.
    return QStringLiteral("Garuda/Vehicles/%1/name").arg(vehicle->vehicleUID()
        ? QStringLiteral("uid-%1").arg(vehicle->vehicleUID())
        : QStringLiteral("sysid-%1").arg(vehicle->id()));
}

QString GarudaVehicleDirectory::name(Vehicle *vehicle) const
{
    if (!current(vehicle)) return {};
    return QSettings().value(key(vehicle), QStringLiteral("GARUDA-%1").arg(vehicle->id(), 2, 10, QLatin1Char('0'))).toString();
}

bool GarudaVehicleDirectory::rename(Vehicle *vehicle, const QString &value)
{
    if (!current(vehicle) || value.trimmed().isEmpty() || value.trimmed().size() > 64) return false;
    QSettings settings;
    settings.setValue(key(vehicle), value.trimmed());
    settings.sync();
    if (settings.status() != QSettings::NoError) return false;
    ++_revision;
    emit changed();
    return true;
}

QString GarudaVehicleDirectory::connection(Vehicle *vehicle) const
{
    if (!current(vehicle)) return {};
    auto link = vehicle->vehicleLinkManager()->primaryLink().lock();
    if (!link) return tr("No active link");
    auto config = link->linkConfiguration();
    const auto type = QMetaEnum::fromType<LinkConfiguration::LinkType>();
    return QStringLiteral("%1 · %2").arg(QString::fromLatin1(type.valueToKey(config->type())), config->name());
}

int GarudaVehicleDirectory::componentId(Vehicle *vehicle) const
{
    return current(vehicle) ? vehicle->defaultComponentId() : -1;
}

bool GarudaVehicleDirectory::identityConflict(Vehicle *vehicle) const
{
    if (!current(vehicle) || vehicle->vehicleUID() == 0) return false;
    auto *vehicles = MultiVehicleManager::instance()->vehicles();
    for (int i = 0; i < vehicles->count(); ++i) {
        auto *other = qobject_cast<Vehicle *>(vehicles->get(i));
        if (other && other != vehicle && other->vehicleUID() == vehicle->vehicleUID()) return true;
    }
    return false;
}

bool GarudaVehicleDirectory::select(Vehicle *vehicle)
{
    if (!current(vehicle) || identityConflict(vehicle)) return false;
    MultiVehicleManager::instance()->setActiveVehicle(vehicle);
    return true;
}
