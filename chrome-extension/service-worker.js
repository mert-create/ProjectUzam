const adDomains = [
    "adnxs.com", "adform.net", "amazon-adsystem.com", "criteo.com", "criteo.net",
    "doubleclick.net", "googleadservices.com", "googlesyndication.com",
    "openx.net", "outbrain.com", "pubmatic.com", "rubiconproject.com",
    "smartadserver.com", "taboola.com", "yieldmo.com"
];

let pending = Promise.resolve();
chrome.action.onClicked.addListener(tab => {
    pending = pending.then(() => toggleSite(tab)).catch(error => {
        console.error("Uzam reklam filtresi değiştirilemedi.", error);
    });
});

async function toggleSite(tab) {
    if (!tab.url || tab.id === undefined) return;
    const url = new URL(tab.url);
    if (url.protocol !== "https:" && url.protocol !== "http:") return;
    const domain = url.hostname.replace(/\.$/, "");
    if (!/^[a-z0-9.-]+$/.test(domain)) return;

    const api = chrome.declarativeNetRequest;
    const rules = await api.getDynamicRules();
    const currentRule = rules.find(rule => rule.condition.topDomains[0] === domain);
    const matchingRule = rules.filter(rule => {
        const scope = rule.condition.topDomains[0];
        return domain === scope || domain.endsWith("." + scope);
    }).sort((first, second) => second.priority - first.priority)[0];
    const enable = matchingRule?.action.type !== "block";
    const id = currentRule?.id ?? rules.reduce((highest, rule) => Math.max(highest, rule.id), 0) + 1;

    await api.updateDynamicRules({
        removeRuleIds: currentRule ? [currentRule.id] : [],
        addRules: [{
            id,
            priority: domain.split(".").length,
            action: { type: enable ? "block" : "allow" },
            condition: { topDomains: [domain], requestDomains: adDomains, excludedResourceTypes: ["main_frame"] }
        }]
    });

    const current = await chrome.tabs.get(tab.id);
    if (current.url === tab.url) await chrome.tabs.reload(tab.id);
}

chrome.runtime.onInstalled.addListener(() => {
    chrome.declarativeNetRequest.setExtensionActionOptions({ displayActionCountAsBadgeText: true })
        .catch(error => console.error("Uzam reklam sayacı açılamadı.", error));
});
