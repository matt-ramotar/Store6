#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class SCS__SkieSuspendWrappersKt, SCSUShort, SCSULong, SCSUInt, SCSUByte, SCSStoreResultRevalidated, SCSStoreResultLoading, SCSStoreResultError, SCSStoreResultData<V>, SCSStoreNamespace, SCSStoreException, SCSStoreErrorPersistence, SCSStoreErrorMissing, SCSStoreErrorFreshnessUnsatisfiable, SCSStoreErrorFetch, SCSStoreErrorConversion, SCSStoreErrorConflict, SCSStoreError, SCSStoreBuilderKt, SCSStoreBuilder<K, V>, SCSSkie_SuspendResultSuccess, SCSSkie_SuspendResultError, SCSSkie_SuspendResultCanceled, SCSSkie_SuspendResult, SCSSkie_SuspendHandler, SCSSkie_CancellationHandler, SCSSkieKotlinStateFlow<T>, SCSSkieKotlinSharedFlow<T>, SCSSkieKotlinOptionalStateFlow<T>, SCSSkieKotlinOptionalSharedFlow<T>, SCSSkieKotlinOptionalMutableStateFlow<T>, SCSSkieKotlinOptionalMutableSharedFlow<T>, SCSSkieKotlinOptionalFlow<T>, SCSSkieKotlinMutableStateFlow<T>, SCSSkieKotlinMutableSharedFlow<T>, SCSSkieKotlinFlow<T>, SCSSkieColdFlowIterator<E>, SCSShort, SCSOrigin, SCSNumber, SCSMutableSet<ObjectType>, SCSMutableDictionary<KeyType, ObjectType>, SCSLong, SCSKotlinThrowable, SCSKotlinRuntimeException, SCSKotlinIllegalStateException, SCSKotlinException, SCSKotlinEnumCompanion, SCSKotlinEnum<E>, SCSKotlinCancellationException, SCSKotlinArray<T>, SCSInt, SCSFreshnessStaleIfError, SCSFreshnessMustBeFresh, SCSFreshnessMaxAge, SCSFreshnessLocalOnly, SCSFreshnessCachedOrFetch, SCSFloat, SCSFetcherResultSuccess<V>, SCSFetcherResultNotModified, SCSFetcherResultError, SCSFetcherResultDeleted, SCSDouble, SCSByte, SCSBoolean, SCSBase, NSString, NSSet<ObjectType>, NSObject, NSNumber, NSMutableSet<ObjectType>, NSMutableDictionary<KeyType, ObjectType>, NSMutableArray<ObjectType>, NSError, NSDictionary<KeyType, ObjectType>, NSArray<ObjectType>;

@protocol SCSStoreResult, SCSStoreMeta, SCSStoreKey, SCSStore, SCSSkie_DispatcherDelegate, SCSKotlinx_coroutines_coreStateFlow, SCSKotlinx_coroutines_coreSharedFlow, SCSKotlinx_coroutines_coreRunnable, SCSKotlinx_coroutines_coreMutableStateFlow, SCSKotlinx_coroutines_coreMutableSharedFlow, SCSKotlinx_coroutines_coreFlowCollector, SCSKotlinx_coroutines_coreFlow, SCSKotlinSuspendFunction1, SCSKotlinIterator, SCSKotlinFunction, SCSKotlinComparable, SCSFreshness, SCSFetcherResult, NSCopying;

// Due to an Obj-C/Swift interop limitation, SKIE cannot generate Swift types with a lambda type argument.
// Example of such type is: A<() -> Unit> where A<T> is a generic class.
// To avoid compilation errors SKIE replaces these type arguments with __SkieLambdaErrorType, resulting in A<__SkieLambdaErrorType>.
// Generated declarations that reference __SkieLambdaErrorType cannot be called in any way and the __SkieLambdaErrorType class cannot be used.
// The original declarations can still be used in the same way as other declarations hidden by SKIE (and with the same limitations as without SKIE).
@interface __SkieLambdaErrorType : NSObject
- (instancetype _Nonnull)init __attribute__((unavailable));
+ (instancetype _Nonnull)new __attribute__((unavailable));
@end

// Due to an Obj-C/Swift interop limitation, SKIE cannot generate Swift code that uses external Obj-C types for which SKIE doesn't know a fully qualified name.
// This problem occurs when custom Cinterop bindings are used because those do not contain the name of the Framework that provides implementation for those binding.
// The name can be configured manually using the SKIE Gradle configuration key 'ClassInterop.CInteropFrameworkName' in the same way as other SKIE features.
// To avoid compilation errors SKIE replaces types with unknown Framework name with __SkieUnknownCInteropFrameworkErrorType.
// Generated declarations that reference __SkieUnknownCInteropFrameworkErrorType cannot be called in any way and the __SkieUnknownCInteropFrameworkErrorType class cannot be used.
@interface __SkieUnknownCInteropFrameworkErrorType : NSObject
- (instancetype _Nonnull)init __attribute__((unavailable));
+ (instancetype _Nonnull)new __attribute__((unavailable));
@end


NS_ASSUME_NONNULL_BEGIN
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunknown-warning-option"
#pragma clang diagnostic ignored "-Wincompatible-property-type"
#pragma clang diagnostic ignored "-Wnullability"

#pragma push_macro("_Nullable_result")
#if !__has_feature(nullability_nullable_result)
#undef _Nullable_result
#define _Nullable_result _Nullable
#endif

__attribute__((swift_name("KotlinBase")))
@interface SCSBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface SCSBase (SCSBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface SCSMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface SCSMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorSCSKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface SCSNumber : NSNumber
- (instancetype)initWithChar:(char)value __attribute__((unavailable));
- (instancetype)initWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
- (instancetype)initWithShort:(short)value __attribute__((unavailable));
- (instancetype)initWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
- (instancetype)initWithInt:(int)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
- (instancetype)initWithLong:(long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
- (instancetype)initWithLongLong:(long long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
- (instancetype)initWithFloat:(float)value __attribute__((unavailable));
- (instancetype)initWithDouble:(double)value __attribute__((unavailable));
- (instancetype)initWithBool:(BOOL)value __attribute__((unavailable));
- (instancetype)initWithInteger:(NSInteger)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
+ (instancetype)numberWithChar:(char)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
+ (instancetype)numberWithShort:(short)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
+ (instancetype)numberWithInt:(int)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
+ (instancetype)numberWithLong:(long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
+ (instancetype)numberWithLongLong:(long long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
+ (instancetype)numberWithFloat:(float)value __attribute__((unavailable));
+ (instancetype)numberWithDouble:(double)value __attribute__((unavailable));
+ (instancetype)numberWithBool:(BOOL)value __attribute__((unavailable));
+ (instancetype)numberWithInteger:(NSInteger)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
@end

__attribute__((swift_name("KotlinByte")))
@interface SCSByte : SCSNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface SCSUByte : SCSNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface SCSShort : SCSNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface SCSUShort : SCSNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface SCSInt : SCSNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface SCSUInt : SCSNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface SCSLong : SCSNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface SCSULong : SCSNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface SCSFloat : SCSNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface SCSDouble : SCSNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface SCSBoolean : SCSNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieColdFlowIterator")))
@interface SCSSkieColdFlowIterator<E> : SCSBase
- (instancetype)initWithFlow:(id<SCSKotlinx_coroutines_coreFlow>)flow __attribute__((swift_name("init(flow:)"))) __attribute__((objc_designated_initializer));
- (void)cancel __attribute__((swift_name("cancel()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)hasNextWithCompletionHandler:(void (^)(SCSBoolean * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("hasNext(completionHandler:)")));
- (E _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlow")))
@protocol SCSKotlinx_coroutines_coreFlow
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinFlow")))
@interface SCSSkieKotlinFlow<__covariant T> : SCSBase <SCSKotlinx_coroutines_coreFlow>
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreSharedFlow")))
@protocol SCSKotlinx_coroutines_coreSharedFlow <SCSKotlinx_coroutines_coreFlow>
@required
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlowCollector")))
@protocol SCSKotlinx_coroutines_coreFlowCollector
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(id _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreMutableSharedFlow")))
@protocol SCSKotlinx_coroutines_coreMutableSharedFlow <SCSKotlinx_coroutines_coreSharedFlow, SCSKotlinx_coroutines_coreFlowCollector>
@required

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(id _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@property (readonly) id<SCSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinMutableSharedFlow")))
@interface SCSSkieKotlinMutableSharedFlow<T> : SCSBase <SCSKotlinx_coroutines_coreMutableSharedFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SCSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreMutableSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreStateFlow")))
@protocol SCSKotlinx_coroutines_coreStateFlow <SCSKotlinx_coroutines_coreSharedFlow>
@required
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreMutableStateFlow")))
@protocol SCSKotlinx_coroutines_coreMutableStateFlow <SCSKotlinx_coroutines_coreStateFlow, SCSKotlinx_coroutines_coreMutableSharedFlow>
@required
- (void)setValue:(id _Nullable)value __attribute__((swift_name("setValue(_:)")));
- (BOOL)compareAndSetExpect:(id _Nullable)expect update:(id _Nullable)update __attribute__((swift_name("compareAndSet(expect:update:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinMutableStateFlow")))
@interface SCSSkieKotlinMutableStateFlow<T> : SCSBase <SCSKotlinx_coroutines_coreMutableStateFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SCSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@property T value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreMutableStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
- (BOOL)compareAndSetExpect:(T)expect update:(T)update __attribute__((swift_name("compareAndSet(expect:update:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalFlow")))
@interface SCSSkieKotlinOptionalFlow<__covariant T> : SCSBase <SCSKotlinx_coroutines_coreFlow>
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalMutableSharedFlow")))
@interface SCSSkieKotlinOptionalMutableSharedFlow<T> : SCSBase <SCSKotlinx_coroutines_coreMutableSharedFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SCSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreMutableSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalMutableStateFlow")))
@interface SCSSkieKotlinOptionalMutableStateFlow<T> : SCSBase <SCSKotlinx_coroutines_coreMutableStateFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SCSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@property T _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreMutableStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
- (BOOL)compareAndSetExpect:(T _Nullable)expect update:(T _Nullable)update __attribute__((swift_name("compareAndSet(expect:update:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalSharedFlow")))
@interface SCSSkieKotlinOptionalSharedFlow<__covariant T> : SCSBase <SCSKotlinx_coroutines_coreSharedFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalStateFlow")))
@interface SCSSkieKotlinOptionalStateFlow<__covariant T> : SCSBase <SCSKotlinx_coroutines_coreStateFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) T _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinSharedFlow")))
@interface SCSSkieKotlinSharedFlow<__covariant T> : SCSBase <SCSKotlinx_coroutines_coreSharedFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinStateFlow")))
@interface SCSSkieKotlinStateFlow<__covariant T> : SCSBase <SCSKotlinx_coroutines_coreStateFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) T value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SCSKotlinx_coroutines_coreStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_CancellationHandler")))
@interface SCSSkie_CancellationHandler : SCSBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (void)cancel __attribute__((swift_name("cancel()")));
@end

__attribute__((swift_name("Skie_DispatcherDelegate")))
@protocol SCSSkie_DispatcherDelegate
@required
- (void)dispatchBlock:(id<SCSKotlinx_coroutines_coreRunnable>)block __attribute__((swift_name("dispatch(block:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendHandler")))
@interface SCSSkie_SuspendHandler : SCSBase
- (instancetype)initWithCancellationHandler:(SCSSkie_CancellationHandler *)cancellationHandler dispatcherDelegate:(id<SCSSkie_DispatcherDelegate>)dispatcherDelegate onResult:(void (^)(SCSSkie_SuspendResult *))onResult __attribute__((swift_name("init(cancellationHandler:dispatcherDelegate:onResult:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Skie_SuspendResult")))
@interface SCSSkie_SuspendResult : SCSBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Canceled")))
@interface SCSSkie_SuspendResultCanceled : SCSSkie_SuspendResult
@property (class, readonly, getter=shared) SCSSkie_SuspendResultCanceled *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)canceled __attribute__((swift_name("init()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Error")))
@interface SCSSkie_SuspendResultError : SCSSkie_SuspendResult
@property (readonly) NSError *error __attribute__((swift_name("error")));
- (instancetype)initWithError:(NSError *)error __attribute__((swift_name("init(error:)"))) __attribute__((objc_designated_initializer));
- (SCSSkie_SuspendResultError *)doCopyError:(NSError *)error __attribute__((swift_name("doCopy(error:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Success")))
@interface SCSSkie_SuspendResultSuccess : SCSSkie_SuspendResult
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(id _Nullable)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (SCSSkie_SuspendResultSuccess *)doCopyValue:(id _Nullable)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("FetcherResult")))
@protocol SCSFetcherResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultDeleted")))
@interface SCSFetcherResultDeleted : SCSBase <SCSFetcherResult>
@property (class, readonly, getter=shared) SCSFetcherResultDeleted *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)deleted __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultError")))
@interface SCSFetcherResultError : SCSBase <SCSFetcherResult>
@property (readonly) SCSKotlinThrowable *cause __attribute__((swift_name("cause")));
- (instancetype)initWithCause:(SCSKotlinThrowable *)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultNotModified")))
@interface SCSFetcherResultNotModified : SCSBase <SCSFetcherResult>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
- (instancetype)initWithEtag:(NSString * _Nullable)etag __attribute__((swift_name("init(etag:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultSuccess")))
@interface SCSFetcherResultSuccess<V> : SCSBase <SCSFetcherResult>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) V value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(V)value etag:(NSString * _Nullable)etag __attribute__((swift_name("init(value:etag:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Freshness")))
@protocol SCSFreshness
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessCachedOrFetch")))
@interface SCSFreshnessCachedOrFetch : SCSBase <SCSFreshness>
@property (class, readonly, getter=shared) SCSFreshnessCachedOrFetch *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)cachedOrFetch __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessLocalOnly")))
@interface SCSFreshnessLocalOnly : SCSBase <SCSFreshness>
@property (class, readonly, getter=shared) SCSFreshnessLocalOnly *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)localOnly __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessMaxAge")))
@interface SCSFreshnessMaxAge : SCSBase <SCSFreshness>
@property (readonly) int64_t notOlderThan __attribute__((swift_name("notOlderThan")));
- (instancetype)initWithNotOlderThan:(int64_t)notOlderThan __attribute__((swift_name("init(notOlderThan:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessMustBeFresh")))
@interface SCSFreshnessMustBeFresh : SCSBase <SCSFreshness>
@property (class, readonly, getter=shared) SCSFreshnessMustBeFresh *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)mustBeFresh __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessStaleIfError")))
@interface SCSFreshnessStaleIfError : SCSBase <SCSFreshness>
@property (class, readonly, getter=shared) SCSFreshnessStaleIfError *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)staleIfError __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("KotlinComparable")))
@protocol SCSKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface SCSKotlinEnum<E> : SCSBase <SCSKotlinComparable>
@property (class, readonly, getter=companion) SCSKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Origin")))
@interface SCSOrigin : SCSKotlinEnum<SCSOrigin *>
@property (class, readonly) SCSOrigin *memory __attribute__((swift_name("memory")));
@property (class, readonly) SCSOrigin *sot __attribute__((swift_name("sot")));
@property (class, readonly) SCSOrigin *fetcher __attribute__((swift_name("fetcher")));
@property (class, readonly) SCSOrigin *overlay __attribute__((swift_name("overlay")));
@property (class, readonly) NSArray<SCSOrigin *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SCSKotlinArray<SCSOrigin *> *)values __attribute__((swift_name("values()")));
@end

__attribute__((swift_name("Store")))
@protocol SCSStore
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(id<SCSStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(SCSStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(id<SCSStoreKey>)key freshness:(id<SCSFreshness>)freshness completionHandler:(void (^)(id _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(id<SCSStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(SCSStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));
- (id<SCSKotlinx_coroutines_coreFlow>)streamKey:(id<SCSStoreKey>)key freshness:(id<SCSFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreBuilder")))
@interface SCSStoreBuilder<K, V> : SCSBase
- (void)fetcherFetch:(id<SCSKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcher(fetch:)")));
- (void)fetcherOfResultFetch:(id<SCSKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcherOfResult(fetch:)")));
@end

__attribute__((swift_name("StoreError")))
@interface SCSStoreError : SCSBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Conflict")))
@interface SCSStoreErrorConflict : SCSStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) id<SCSStoreMeta> _Nullable serverMeta __attribute__((swift_name("serverMeta")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Conversion")))
@interface SCSStoreErrorConversion : SCSStoreError
@property (readonly) SCSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Fetch")))
@interface SCSStoreErrorFetch : SCSStoreError
@property (readonly) SCSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.FreshnessUnsatisfiable")))
@interface SCSStoreErrorFreshnessUnsatisfiable : SCSStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Missing")))
@interface SCSStoreErrorMissing : SCSStoreError
@property (readonly) id<SCSStoreKey> key __attribute__((swift_name("key")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Persistence")))
@interface SCSStoreErrorPersistence : SCSStoreError
@property (readonly) SCSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((swift_name("KotlinThrowable")))
@interface SCSKotlinThrowable : SCSBase
@property (readonly) SCSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString * _Nullable message __attribute__((swift_name("message")));
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   kotlin.experimental.ExperimentalNativeApi
*/
- (SCSKotlinArray<NSString *> *)getStackTrace __attribute__((swift_name("getStackTrace()")));
- (void)printStackTrace __attribute__((swift_name("printStackTrace()")));
- (NSString *)description __attribute__((swift_name("description()")));
- (NSError *)asError __attribute__((swift_name("asError()")));
@end

__attribute__((swift_name("KotlinException")))
@interface SCSKotlinException : SCSKotlinThrowable
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinRuntimeException")))
@interface SCSKotlinRuntimeException : SCSKotlinException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreException")))
@interface SCSStoreException : SCSKotlinRuntimeException
@property (readonly) SCSStoreError *error __attribute__((swift_name("error")));
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
- (instancetype)initWithCause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@end

__attribute__((swift_name("StoreKey")))
@protocol SCSStoreKey
@required
- (NSString *)canonicalId __attribute__((swift_name("canonicalId()")));
@property (readonly, getter=namespace) SCSStoreNamespace *namespace_ __attribute__((swift_name("namespace_")));
@end

__attribute__((swift_name("StoreMeta")))
@protocol SCSStoreMeta
@required
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) int64_t writtenAtEpochMillis __attribute__((swift_name("writtenAtEpochMillis")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreNamespace")))
@interface SCSStoreNamespace : SCSBase
@property (readonly) NSString *value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("StoreResult")))
@protocol SCSStoreResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultData")))
@interface SCSStoreResultData<V> : SCSBase <SCSStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@property (readonly) BOOL isStale __attribute__((swift_name("isStale")));
@property (readonly) SCSOrigin *origin __attribute__((swift_name("origin")));
@property (readonly) BOOL refreshing __attribute__((swift_name("refreshing")));
@property (readonly) V _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultError")))
@interface SCSStoreResultError : SCSBase <SCSStoreResult>
@property (readonly) SCSStoreError *error __attribute__((swift_name("error")));
@property (readonly) BOOL servedStale __attribute__((swift_name("servedStale")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultLoading")))
@interface SCSStoreResultLoading : SCSBase <SCSStoreResult>
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultRevalidated")))
@interface SCSStoreResultRevalidated : SCSBase <SCSStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreBuilderKt")))
@interface SCSStoreBuilderKt : SCSBase
+ (id<SCSStore>)storeConfigure:(void (^)(SCSStoreBuilder<id<SCSStoreKey>, id> *))configure __attribute__((swift_name("store(configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("__SkieSuspendWrappersKt")))
@interface SCS__SkieSuspendWrappersKt : SCSBase
+ (void)Skie_Suspend__0__clearDispatchReceiver:(id<SCSStore>)dispatchReceiver key:(id<SCSStoreKey>)key suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__0__clear(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__10__hasNextDispatchReceiver:(SCSSkieColdFlowIterator<id> *)dispatchReceiver suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__10__hasNext(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__1__clearAllDispatchReceiver:(id<SCSStore>)dispatchReceiver suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__1__clearAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__2__clearNamespaceDispatchReceiver:(id<SCSStore>)dispatchReceiver namespace:(SCSStoreNamespace *)namespace_ suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__2__clearNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__3__getDispatchReceiver:(id<SCSStore>)dispatchReceiver key:(id<SCSStoreKey>)key freshness:(id<SCSFreshness>)freshness suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__3__get(dispatchReceiver:key:freshness:suspendHandler:)")));
+ (void)Skie_Suspend__4__invalidateDispatchReceiver:(id<SCSStore>)dispatchReceiver key:(id<SCSStoreKey>)key suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__4__invalidate(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__5__invalidateAllDispatchReceiver:(id<SCSStore>)dispatchReceiver suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__5__invalidateAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__6__invalidateNamespaceDispatchReceiver:(id<SCSStore>)dispatchReceiver namespace:(SCSStoreNamespace *)namespace_ suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__6__invalidateNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__7__collectDispatchReceiver:(id<SCSKotlinx_coroutines_coreFlow>)dispatchReceiver collector:(id<SCSKotlinx_coroutines_coreFlowCollector>)collector suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__7__collect(dispatchReceiver:collector:suspendHandler:)")));
+ (void)Skie_Suspend__8__emitDispatchReceiver:(id<SCSKotlinx_coroutines_coreFlowCollector>)dispatchReceiver value:(id _Nullable)value suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__8__emit(dispatchReceiver:value:suspendHandler:)")));
+ (void)Skie_Suspend__9__invokeDispatchReceiver:(id<SCSKotlinSuspendFunction1>)dispatchReceiver p1:(id _Nullable)p1 suspendHandler:(SCSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__9__invoke(dispatchReceiver:p1:suspendHandler:)")));
@end

__attribute__((swift_name("KotlinIllegalStateException")))
@interface SCSKotlinIllegalStateException : SCSKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   kotlin.SinceKotlin(version="1.4")
*/
__attribute__((swift_name("KotlinCancellationException")))
@interface SCSKotlinCancellationException : SCSKotlinIllegalStateException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SCSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreRunnable")))
@protocol SCSKotlinx_coroutines_coreRunnable
@required
- (void)run __attribute__((swift_name("run()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface SCSKotlinEnumCompanion : SCSBase
@property (class, readonly, getter=shared) SCSKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface SCSKotlinArray<T> : SCSBase
@property (readonly) int32_t size __attribute__((swift_name("size")));
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(SCSInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<SCSKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@end

__attribute__((swift_name("KotlinFunction")))
@protocol SCSKotlinFunction
@required
@end

__attribute__((swift_name("KotlinSuspendFunction1")))
@protocol SCSKotlinSuspendFunction1 <SCSKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeP1:(id _Nullable)p1 completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(p1:completionHandler:)")));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol SCSKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
