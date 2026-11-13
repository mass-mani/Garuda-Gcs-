#include "GarudaSwarmController.h"

#include "GarudaVehicleDirectory.h"
#include "MultiVehicleManager.h"
#include "QmlObjectListModel.h"
#include "Vehicle.h"

#include <QSettings>
#include <QUuid>

GarudaSwarmController::GarudaSwarmController(QObject *parent) : QObject(parent)
{
    load();
    auto *manager = MultiVehicleManager::instance();
    for (int i = 0; i < manager->vehicles()->count(); ++i) {
        if (auto *vehicle = qobject_cast<Vehicle *>(manager->vehicles()->get(i))) vehicleKey(vehicle);
    }
    connect(manager, &MultiVehicleManager::vehicleAdded, this, [this](Vehicle *vehicle) {
        vehicleKey(vehicle);
        connectionsChanged();
    });
    connect(manager, &MultiVehicleManager::vehicleRemoved, this, [this](Vehicle *vehicle) {
        _sessionKeys.remove(vehicle);
        connectionsChanged();
    });
}

bool GarudaSwarmController::current(Vehicle *vehicle) const
{
    return vehicle && MultiVehicleManager::instance()->getVehicleById(vehicle->id()) == vehicle;
}

QString GarudaSwarmController::vehicleKey(Vehicle *vehicle)
{
    if (!current(vehicle)) return {};
    if (vehicle->vehicleUID()) return QStringLiteral("uid:%1").arg(vehicle->vehicleUID());
    if (!_sessionKeys.contains(vehicle)) {
        _sessionKeys.insert(vehicle, QStringLiteral("sysid:%1:session:%2").arg(vehicle->id()).arg(QUuid::createUuid().toString(QUuid::WithoutBraces)));
    }
    return _sessionKeys.value(vehicle);
}

int GarudaSwarmController::groupIndex(const QString &name) const
{
    for (int i = 0; i < _groups.size(); ++i) {
        if (_groups[i].name.compare(name, Qt::CaseInsensitive) == 0) return i;
    }
    return -1;
}

QVariantList GarudaSwarmController::groups() const
{
    QVariantList result;
    for (const Group &group : _groups) {
        QVariantMap item;
        item.insert(QStringLiteral("name"), group.name);
        item.insert(QStringLiteral("count"), group.members.size());
        result.append(item);
    }
    return result;
}

void GarudaSwarmController::load()
{
    _groups.clear();
    const QVariantList saved = QSettings().value(QStringLiteral("Garuda/Swarm/groups")).toList();
    for (const QVariant &entry : saved) {
        const QVariantMap value = entry.toMap();
        const QString name = value.value(QStringLiteral("name")).toString().trimmed();
        if (name.isEmpty() || name.size() > 48 || groupIndex(name) >= 0) continue;
        Group group{name, value.value(QStringLiteral("members")).toStringList()};
        group.members.removeDuplicates();
        _groups.append(group);
    }
}

void GarudaSwarmController::save() const
{
    QVariantList saved;
    for (const Group &group : _groups) {
        QVariantMap entry;
        entry.insert(QStringLiteral("name"), group.name);
        entry.insert(QStringLiteral("members"), group.members);
        saved.append(entry);
    }
    QSettings settings;
    settings.setValue(QStringLiteral("Garuda/Swarm/groups"), saved);
    settings.sync();
}

bool GarudaSwarmController::createGroup(const QString &value)
{
    const QString name = value.trimmed();
    if (name.isEmpty() || name.size() > 48 || groupIndex(name) >= 0) return false;
    _groups.append({name, {}});
    _selectedGroup = name;
    _status = tr("Group created. Add connected vehicles before activating it.");
    save();
    emit changed();
    return true;
}

bool GarudaSwarmController::deleteGroup(const QString &name)
{
    const int index = groupIndex(name);
    if (index < 0) return false;
    if (_activeGroup.compare(name, Qt::CaseInsensitive) == 0 && _enabled) disableGroup();
    _groups.removeAt(index);
    if (_selectedGroup.compare(name, Qt::CaseInsensitive) == 0) _selectedGroup.clear();
    save();
    emit changed();
    return true;
}

void GarudaSwarmController::editGroup(const QString &name)
{
    if (groupIndex(name) < 0 || _selectedGroup.compare(name, Qt::CaseInsensitive) == 0) return;
    _selectedGroup = name;
    emit changed();
}

bool GarudaSwarmController::containsVehicle(const QString &groupName, Vehicle *vehicle) const
{
    const int index = groupIndex(groupName);
    if (index < 0 || !current(vehicle)) return false;
    const QString key = vehicle->vehicleUID()
            ? QStringLiteral("uid:%1").arg(vehicle->vehicleUID())
            : _sessionKeys.value(vehicle);
    return !key.isEmpty() && _groups[index].members.contains(key);
}

bool GarudaSwarmController::setMember(const QString &groupName, Vehicle *vehicle, bool included)
{
    const int index = groupIndex(groupName);
    if (index < 0 || !current(vehicle)) return false;
    GarudaVehicleDirectory directory;
    if (included && directory.identityConflict(vehicle)) return false;
    const QString key = vehicleKey(vehicle);
    if (key.isEmpty()) return false;
    QStringList &members = _groups[index].members;
    if (_enabled && _activeGroup.compare(groupName, Qt::CaseInsensitive) == 0) disableGroup();
    if (included && !members.contains(key)) members.append(key);
    else if (!included) members.removeAll(key);
    save();
    _status = included ? tr("Vehicle added to group.") : tr("Vehicle removed from group.");
    emit changed();
    return true;
}

bool GarudaSwarmController::activateGroup(const QString &name)
{
    // Start from an empty selection so a failed activation cannot leave an
    // older group selected while the UI reports that this activation failed.
    if (_enabled) disableGroup();

    const int index = groupIndex(name);
    if (index < 0 || _groups[index].members.isEmpty()) {
        _status = tr("Choose a group with at least one member.");
        emit changed();
        return false;
    }

    QHash<QString, QList<Vehicle *>> connected;
    auto *manager = MultiVehicleManager::instance();
    GarudaVehicleDirectory directory;
    for (int i = 0; i < manager->vehicles()->count(); ++i) {
        auto *vehicle = qobject_cast<Vehicle *>(manager->vehicles()->get(i));
        if (vehicle && !directory.identityConflict(vehicle)) connected[vehicleKey(vehicle)].append(vehicle);
    }

    QList<Vehicle *> recipients;
    for (const QString &member : _groups[index].members) {
        const auto matches = connected.value(member);
        if (matches.size() != 1) {
            _status = tr("Group activation blocked: a saved member is disconnected, ambiguous, or from an earlier session.");
            emit changed();
            return false;
        }
        recipients.append(matches.first());
    }

    manager->deselectAllVehicles();
    for (Vehicle *vehicle : recipients) manager->selectVehicle(vehicle->id());
    _selectedGroup = name;
    _activeGroup = name;
    _enabled = true;
    _status = tr("Group active for selection only. No commands were sent.");
    emit changed();
    return true;
}

void GarudaSwarmController::disableGroup()
{
    if (_enabled) MultiVehicleManager::instance()->deselectAllVehicles();
    _enabled = false;
    _activeGroup.clear();
    _status = tr("Group selection disabled.");
    emit changed();
}

void GarudaSwarmController::connectionsChanged()
{
    if (_enabled) {
        MultiVehicleManager::instance()->deselectAllVehicles();
        _enabled = false;
        _activeGroup.clear();
        _status = tr("Connections changed. Review the group and activate it again.");
    }
    emit changed();
}
