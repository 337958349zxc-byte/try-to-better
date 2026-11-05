const selected=new URLSearchParams(location.search).get('date');
const valid=selected&&/^\d{4}-\d{2}-\d{2}$/.test(selected);
document.getElementById('date').textContent=valid?`所选日期：${selected} · 尚未提供公开菜单`:'分享版菜谱占位页';
