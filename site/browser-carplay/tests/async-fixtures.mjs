export const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
export const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
