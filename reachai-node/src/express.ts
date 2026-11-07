import express, { type Router, type RequestHandler } from 'express';
import { ReachAI } from './core';

/** Mount only on the trusted business service. The business login/CSRF middleware remains the host's responsibility. */
export function createReachRouter(reach: ReachAI): Router {
  reach.mount();
  const router = express.Router();
  const safe = (handler: RequestHandler): RequestHandler => (req,res,next) => { Promise.resolve(handler(req,res,next)).catch(next); };
  router.use(express.json({ limit: '1mb', strict: true }));
  router.post('/reachai/registry/capabilities/sync', safe(async (req,res) => {
    if (!await reach.verifyCallback(req.get('X-ReachAI-App-Key') || '',req.get('X-ReachAI-Timestamp') || '',req.get('X-ReachAI-Nonce') || '',req.get('X-ReachAI-Signature') || '')) return res.status(401).json({error:'UNAUTHORIZED'});
    try {
      const result = await reach.sync('SDK_CALLBACK');
      const payload = result.data && typeof result.data === 'object' ? result.data as Record<string,unknown> : result;
      return res.json({instanceId:reach.instanceId,capabilityCount:payload.received,httpApiCount:0,registryResponse:JSON.stringify(result)});
    } catch { return res.status(502).json({error:'SYNC_FAILED'}); }
  }));
  router.post('/reachai/capabilities/:name/invoke', safe(async (req,res) => {
    const result=await reach.invoke(req.params.name,req.get('X-ReachAI-Invocation-Token') || '',req.body);
    return res.status(result.status).json(result.body);
  }));
  router.post('/reachai/embed/token', safe(async (req,res) => {
    const result=await reach.embed(req,req.body); return res.status(result.status).json(result.body);
  }));
  router.use((error: unknown, _req: express.Request, res: express.Response, next: express.NextFunction) => {
    if (res.headersSent) return next(error);
    const errorStatus = typeof error === 'object' && error && 'status' in error ? (error as {status:unknown}).status : undefined;
    const status = errorStatus === 413 ? 413 : errorStatus === 400 ? 400 : 500;
    return res.status(status).json({error:status === 500 ? 'INTERNAL_ERROR' : 'INVALID_REQUEST'});
  });
  return router;
}
