#pragma once

#include <QObject>
#include <QHash>
#include <QVariantList>
#include <QStringList>

class Vehicle;

// Persistent, identity-checked vehicle groups. This controller only changes QGC selection;
// it does not issue MAVLink commands.
class GarudaSwarmController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QVariantList groups READ groups NOTIFY changed)
    Q_PROPERTY(QString selectedGroup READ selectedGroup NOTIFY changed)
    Q_PROPERTY(QString activeGroup READ activeGroup NOTIFY changed)
    Q_PROPERTY(bool enabled READ enabled NOTIFY changed)
    Q_PROPERTY(QString status READ status NOTIFY changed)
public:
    explicit GarudaSwarmController(QObject *parent = nullptr);
    QVariantList groups() const;
    QString selectedGroup() const { return _selectedGroup; }
    QString activeGroup() const { return _activeGroup; }
    bool enabled() const { return _enabled; }
    QString status() const { return _status; }

    Q_INVOKABLE bool createGroup(const QString &name);
    Q_INVOKABLE bool deleteGroup(const QString &name);
    Q_INVOKABLE void editGroup(const QString &name);
    Q_INVOKABLE bool containsVehicle(const QString &groupName, Vehicle *vehicle) const;
    Q_INVOKABLE bool setMember(const QString &groupName, Vehicle *vehicle, bool included);
    Q_INVOKABLE bool activateGroup(const QString &name);
    Q_INVOKABLE void disableGroup();

signals:
    void changed();

private:
    struct Group { QString name; QStringList members; };
    QString vehicleKey(Vehicle *vehicle);
    bool current(Vehicle *vehicle) const;
    int groupIndex(const QString &name) const;
    void load();
    void save() const;
    void connectionsChanged();

    QList<Group> _groups;
    QHash<Vehicle *, QString> _sessionKeys;
    QString _selectedGroup;
    QString _activeGroup;
    QString _status;
    bool _enabled = false;
};
