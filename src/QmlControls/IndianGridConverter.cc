#include "IndianGridConverter.h"

#include <QtMath>
#include <cmath>

namespace {
struct Zone { const char* name; double lon0, lat0, k0, x0, y0; };
constexpr Zone zones[] = {
    {"0",68,39.5,.99846154,2153866.4,2368292.9},
    {"IA",68,32.5,.99878641,2743196.4,914398.8},
    {"IB",90,32.5,.99878641,2743196.4,914398.8},
    {"IIA",74,26,.99878641,2743196.4,914398.8},
    {"IIB",90,26,.99878641,2743196.4,914398.8},
    {"IIIA",80,19,.99878641,2743196.4,914398.8},
    {"IIIB",100,19,.99878641,2743196.4,914398.8},
    {"IVA",80,12,.99878641,2743196.4,914398.8},
    {"IVB",104,12,.99878641,2743196.4,914398.8},
};
constexpr double a = 6377301.243;           // published Everest 1956 approximation
constexpr double invF = 300.8017;
constexpr double e2 = 2.0/invF - 1.0/(invF*invF);
constexpr double b = a * (1.0 - 1.0/invF);
constexpr double wgsA = 6378137.0;
constexpr double wgsE2 = 0.0066943799901413165;
constexpr double rad = M_PI / 180.0;

const Zone* findZone(const QString& name) {
    for (const auto& zone : zones) if (name.compare(QLatin1String(zone.name), Qt::CaseInsensitive) == 0) return &zone;
    return nullptr;
}

// Published boundaries are incomplete. Never guess zone IB or boundaries.
QString automaticZone(double lat, double lon) {
    if (lat > 35.583333 && lon >= 68 && lon <= 98) return QStringLiteral("0");
    if (lat > 28 && lat < 35.583333 && lon >= 68 && lon < 82) return QStringLiteral("IA");
    if (lat > 21 && lat < 28 && lon >= 68 && lon < 82) return QStringLiteral("IIA");
    if (lat > 21 && lat < 28 && lon > 82 && lon < 95) return QStringLiteral("IIB");
    if (lat > 15 && lat < 21 && lon >= 68 && lon < 90) return QStringLiteral("IIIA");
    if (lat > 15 && lat < 21 && lon > 94 && lon < 105) return QStringLiteral("IIIB");
    if (lat < 15 && lat >= 6 && lon >= 68 && lon < 90) return QStringLiteral("IVA");
    if (lat < 15 && lat >= 6 && lon > 94 && lon < 105) return QStringLiteral("IVB");
    return {};
}

// Geocentric three-translation from the published approximation. No vertical
// coordinate is claimed; ellipsoid height zero is used only in this calculation.
void geodeticToXyz(double lat, double lon, double axis, double eccentricity2,
                   double& x, double& y, double& z) {
    const double p = lat*rad, l = lon*rad;
    const double n = axis/std::sqrt(1-eccentricity2*std::sin(p)*std::sin(p));
    x=n*std::cos(p)*std::cos(l);
    y=n*std::cos(p)*std::sin(l);
    z=n*(1-eccentricity2)*std::sin(p);
}
void xyzToGeodetic(double x, double y, double z, double axis, double eccentricity2,
                   double& lat, double& lon) {
    lon = std::atan2(y,x)/rad;
    const double pxy = std::hypot(x,y);
    double phi=std::atan2(z,pxy*(1-eccentricity2));
    for (int i=0;i<12;i++) {
        const double n=axis/std::sqrt(1-eccentricity2*std::sin(phi)*std::sin(phi));
        const double next=std::atan2(z+eccentricity2*n*std::sin(phi),pxy);
        if (std::abs(next-phi)<1e-14) break;
        phi=next;
    }
    lat=phi/rad;
}
double m(double phi) { return std::cos(phi)/std::sqrt(1-e2*std::sin(phi)*std::sin(phi)); }
double t(double phi) { const double e=std::sqrt(e2); return std::tan(M_PI/4-phi/2)/std::pow((1-e*std::sin(phi))/(1+e*std::sin(phi)),e/2); }
QVariantMap error(const QString& message) { return {{"valid",false},{"error",message},{"approximate",true}}; }
QVariantMap result(const Zone& zone, double east, double north, double lat, double lon) {
    return {{"valid",true},{"zone",QLatin1String(zone.name)},{"easting",east},{"northing",north},
            {"latitude",lat},{"longitude",lon},{"approximate",true},
            {"warning",QStringLiteral("Approximate published Indian datum and projection. Not validated for navigation or waypoint entry.")},
            {"definition",QStringLiteral("Indian Grid approximation v1; Everest 1956; LCC 1SP equivalent; 3-parameter datum shift")}};
}
}

QStringList IndianGridConverter::zones() const { return {"0","IA","IB","IIA","IIB","IIIA","IIIB","IVA","IVB"}; }

QVariantMap IndianGridConverter::fromWgs84(double latitude, double longitude, const QString& requestedZone) const {
    if (!std::isfinite(latitude) || !std::isfinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
        return error(QStringLiteral("Invalid WGS84 latitude or longitude"));
    const QString name=requestedZone.trimmed().isEmpty() ? automaticZone(latitude,longitude) : requestedZone.trimmed().toUpper();
    if (name.isEmpty()) return error(QStringLiteral("Zone uncertain; select a zone explicitly"));
    const Zone* zone=findZone(name);
    if (!zone) return error(QStringLiteral("Unknown Indian Grid zone"));
    double x,y,z,lat,lon;
    geodeticToXyz(latitude,longitude,wgsA,wgsE2,x,y,z);
    // Indian datum to WGS84: +295,+736,+257 metres (published approximation).
    xyzToGeodetic(x-295,y-736,z-257,a,e2,lat,lon);
    const double phi0=zone->lat0*rad;
    const double n=std::sin(phi0);
    const double F=m(phi0)/(n*std::pow(t(phi0),n));
    const double rho=a*zone->k0*F*std::pow(t(lat*rad),n);
    const double rho0=a*zone->k0*F*std::pow(t(phi0),n);
    const double theta=n*(lon-zone->lon0)*rad;
    return result(*zone,zone->x0+rho*std::sin(theta),zone->y0+rho0-rho*std::cos(theta),latitude,longitude);
}

QVariantMap IndianGridConverter::toWgs84(const QString& name, double easting, double northing) const {
    const Zone* zone=findZone(name.trimmed());
    if (!zone) return error(QStringLiteral("Unknown Indian Grid zone"));
    if (!std::isfinite(easting) || !std::isfinite(northing) || easting < 0 || northing < 0 || easting > 10000000 || northing > 10000000)
        return error(QStringLiteral("Invalid full easting or northing; abbreviated references are ambiguous"));
    const double phi0=zone->lat0*rad, n=std::sin(phi0);
    const double F=m(phi0)/(n*std::pow(t(phi0),n));
    const double rho0=a*zone->k0*F*std::pow(t(phi0),n);
    const double dx=easting-zone->x0, dy=rho0-(northing-zone->y0);
    const double rho=std::copysign(std::hypot(dx,dy),n);
    const double theta=std::atan2(dx,dy);
    const double target=std::pow(rho/(a*zone->k0*F),1/n);
    const double eccentricity=std::sqrt(e2);
    double phi=M_PI/2-2*std::atan(target);
    for(int i=0;i<15;i++) {
        const double next=M_PI/2-2*std::atan(target*std::pow((1-eccentricity*std::sin(phi))/(1+eccentricity*std::sin(phi)),eccentricity/2));
        if(std::abs(next-phi)<1e-14) break;
        phi=next;
    }
    double x,y,z,lat,lon;
    geodeticToXyz(phi/rad,zone->lon0+theta/n/rad,a,e2,x,y,z);
    xyzToGeodetic(x+295,y+736,z+257,wgsA,wgsE2,lat,lon);
    if (!std::isfinite(lat) || !std::isfinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
        return error(QStringLiteral("Grid coordinate did not resolve to a valid location"));
    return result(*zone,easting,northing,lat,lon);
}
