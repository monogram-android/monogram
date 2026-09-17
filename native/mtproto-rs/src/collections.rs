pub use compact_str::CompactString;
pub use rapidhash::fast::{
    HashMapExt, HashSetExt, RapidHashMap as HashMap, RapidHashSet as HashSet,
};
pub use smallvec::SmallVec;

pub type IndexMap<K, V> = indexmap::IndexMap<K, V, rapidhash::fast::RandomState>;
