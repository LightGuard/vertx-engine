package com.redhat.vertx.pipeline;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.redhat.vertx.Engine;
import com.redhat.vertx.pipeline.json.TemplatedJsonObject;
import com.redhat.vertx.pipeline.templates.JinjaTemplateProcessor;
import io.reactivex.*;
import io.reactivex.disposables.Disposable;
import io.vertx.core.json.JsonObject;

/**
 * Abstract step offers code for managing steps that might execute longer
 */
public abstract class AbstractStep extends DocBasedDisposableManager implements Step {
    protected Logger logger = Logger.getLogger(this.getClass().getName());
    protected JsonObject vars;
    protected Engine engine;
    protected String name;
    private long timeout;
    private String registerTo;
    private boolean initialized;

    @Override
    public Completable init(Engine engine, JsonObject config) {
        assert !initialized;
        this.engine = engine;
        name = config.getString("name");
        vars = config.getJsonObject("vars", new JsonObject());
        timeout = config.getLong("timeout_ms", 5000L);
        registerTo = config.getString("register");
        initialized = true;
        return Completable.complete();
    }

    /**
     * Responsibilities:
     * <ul>
     *     <li>Execute its step</li>
     *     <li>Defer the step whose dependencies are not met</li>
     *     <li>Retry the step whose dependencies are not met when a change happens</li>
     *     <li>Fetch the document from the engine</li>
     * </ul>
     *
     * @param uuid the key for the document being built (get it from the engine)
     * @return a Maybe containing a JsonObject with one entry, the key equal to the "register" config object,
     * or simply complete if the step has executed without returning a value.
     */
    @Override
    public final Maybe<JsonObject> execute(String uuid) {
        assert initialized;
        return Maybe.create(source -> execute0(uuid, source, new ArrayList<>(2)) );
    }

    /**
     * @param uuid   The UUID of the document to be operated on
     * @param source The SingleEmitter for the AbstractStep execution we're attmembempting to complete
     */
    private void execute0(String uuid, MaybeEmitter<JsonObject> source, List<Disposable> listener) {
        /*
         * The gist of this function is:
         *
         * try {
         *    executeSlow(success -> pass it back,
         *    error -> if it was StepDependencyNotMetException and we don't already have a listener, register a listener
         *    and try again.)
         *
         * } catch (StepDependencyNotMetException e) {
         *    register a listener and defer execution until after a change
         * }
         */
        Maybe<Object> result = executeSlow(getEnvironment(uuid));
        addDisposable(uuid,result
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .subscribe(resultReturn -> {
                    logger.finest(() -> "Step " + name + " returned: " + resultReturn.toString());
                    listener.forEach(Disposable::dispose);
                    logger.finest(() -> "Removing from listener " + System.identityHashCode(listener) + " size=" +
                            listener.size() + " disposables for step " + name + ".");
                    listener.clear();
                    if (registerTo != null) {
                        source.onSuccess(new JsonObject().put(registerTo,resultReturn));
                    } else {
                        source.onComplete();
                    }
                },
                err -> {
                    if (err instanceof StepDependencyNotMetException) {
                        if (listener.isEmpty()) {
                            logger.finest(() -> "Step " + name + " listening for a change.");
                            listener.add(engine.getEventBus()
                                    .consumer(EventBusMessage.DOCUMENT_CHANGED).toObservable()
                                    .filter(msg -> uuid.equals(msg.headers().get("uuid")))
                                    .subscribe(msg -> execute0(uuid, source, listener)));
                        }
                    } else if (err != null) {
                        source.tryOnError(err);
                    }
                }));
    }

    protected JsonObject getEnvironment(String uuid) {
         JsonObject vars = this.vars.copy();
         vars.put("doc",getDocument(uuid));
         vars.put("system", engine.getSystemConfig());
         return new TemplatedJsonObject(vars,engine.getTemplateProcessor(),"doc", "system");
    }

    /**
     * @param uuid The UUID of the document under construction
     * @return The document (without local step variables) from the engine, based on the given UUID
     */
    protected JsonObject getDocument(String uuid) {
        return engine.getDocument(uuid);
    }

    /**
     * Override this if the work is non-blocking.
     *
     * @param env A {@link JsonObject} consisting of the variables for this step, plus a special one called "doc"
     *            containing the document being constructed.
     * @return a JSON-compatible object, JsonObject, JsonArray, or String
     * @throws StepDependencyNotMetException if this step should be retried later after the document has been changed
     */
    public Object execute(JsonObject env) throws StepDependencyNotMetException {
        return null;
    }

    /**
     * Override this if the work is slow enough to need to return the result later.
     *
     * @param env A {@link JsonObject} consisting of the variables for this step, plus a special one called "doc"
     *            containing the document being constructed.
     * @return a JSON-compatible object, JsonObject, JsonArray, or String

     */
    protected Maybe<Object> executeSlow(JsonObject env) {
        try {
            Object rval = execute(env);
            return (rval == null || registerTo == null) ?
                    Maybe.empty() : Maybe.just(rval);
        } catch (StepDependencyNotMetException e) {
            return Maybe.error(e);
        }
    }

    protected void addDisposable(JsonObject env, Disposable disposable) {
        super.addDisposable(env.getJsonObject("doc").getString(Engine.DOC_UUID), disposable);
    }
}
